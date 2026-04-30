package com.zhituagent.orchestrator;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhituagent.tool.ToolDefinition;
import com.zhituagent.tool.ToolRegistry;
import com.zhituagent.tool.ToolResult;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs multiple LLM-issued tool calls in parallel, capturing failures as
 * {@link ToolResult#success()} = false instead of letting exceptions bubble. The
 * captured failures are returned to the caller so they can be replayed back to
 * the LLM as observations (the "tool error fallback" pattern from the OpenAI
 * cookbook / Anthropic tool-use guide).
 *
 * <p>Tools that opt into {@link ToolDefinition#requiresApproval()} go through a
 * Human-in-the-Loop gate: on first sight the call is parked in
 * {@link PendingToolCallStore} and the caller receives an
 * {@code awaiting_approval} observation; the same tool call only runs when the
 * caller resends the chat with {@code metadata.approvedToolCallId} set to a
 * previously-approved entry.
 */
@Component
public class ToolCallExecutor {

    private static final Logger log = LoggerFactory.getLogger(ToolCallExecutor.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> ARG_MAP_TYPE = new TypeReference<>() {
    };
    public static final String METADATA_APPROVED_ID = "approvedToolCallId";
    public static final String METADATA_SESSION_ID = "sessionId";
    public static final String AWAITING_APPROVAL_SUMMARY_PREFIX = "awaiting_approval: ";

    private final ToolRegistry toolRegistry;
    private final PendingToolCallStore pendingToolCallStore;
    private final ExecutorService executor;
    private final LoopDetector loopDetector = new LoopDetector();

    @Autowired
    public ToolCallExecutor(ToolRegistry toolRegistry, PendingToolCallStore pendingToolCallStore) {
        this.toolRegistry = toolRegistry;
        this.pendingToolCallStore = pendingToolCallStore;
        this.executor = Executors.newFixedThreadPool(4, namedThreadFactory("tool-exec"));
    }

    public ToolCallExecutor(ToolRegistry toolRegistry) {
        this(toolRegistry, new PendingToolCallStore());
    }

    public List<ToolExecution> executeAll(List<ToolExecutionRequest> toolCalls) {
        return executeAll(toolCalls, Map.of());
    }

    public List<ToolExecution> executeAll(List<ToolExecutionRequest> toolCalls, Map<String, Object> metadata) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return List.of();
        }
        Map<String, Object> safeMetadata = metadata == null ? Map.of() : metadata;

        List<CompletableFuture<ToolExecution>> futures = new ArrayList<>(toolCalls.size());
        for (ToolExecutionRequest request : toolCalls) {
            futures.add(CompletableFuture.supplyAsync(() -> executeOne(request, safeMetadata), executor));
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture<?>[0])).join();

        List<ToolExecution> results = new ArrayList<>(futures.size());
        for (CompletableFuture<ToolExecution> future : futures) {
            results.add(future.join());
        }
        return List.copyOf(results);
    }

    private ToolExecution executeOne(ToolExecutionRequest request, Map<String, Object> metadata) {
        String name = request.name();
        ToolDefinition tool = toolRegistry.find(name).orElse(null);
        if (tool == null) {
            log.warn("LLM 选择了未注册工具 chat.tool.unknown name={} arguments={}", name, request.arguments());
            ToolResult notFound = new ToolResult(name, false, "tool not registered: " + name, Map.of());
            return new ToolExecution(request, notFound);
        }

        int callCount = loopDetector.record(name, request.arguments());
        if (callCount >= LoopDetector.loopThreshold()) {
            log.warn("工具调用环检测命中 chat.tool.loop_detected name={} count={}", name, callCount);
            ToolResult loop = new ToolResult(
                    name,
                    false,
                    "tool call loop detected: tool '" + name + "' invoked " + callCount
                            + " times with identical arguments. Please change arguments or pick a different tool.",
                    Map.of()
            );
            return new ToolExecution(request, loop);
        }

        Map<String, Object> arguments = parseArguments(request.arguments());

        JsonArgumentValidator.ValidationResult validation = JsonArgumentValidator.validate(
                tool.parameterSchema(),
                arguments
        );
        if (!validation.valid()) {
            log.warn("工具参数 schema 校验失败 chat.tool.schema_violation name={} errors={}", name, validation.errors());
            ToolResult invalid = new ToolResult(
                    name,
                    false,
                    "argument validation failed: " + validation.formatErrors() + ". Please re-issue the call with correct arguments matching the tool schema.",
                    Map.of("validationErrors", validation.errors())
            );
            return new ToolExecution(request, invalid);
        }

        if (tool.requiresApproval()) {
            ApprovalOutcome outcome = checkApproval(name, request.arguments(), arguments, metadata);
            if (outcome.pending() != null) {
                return new ToolExecution(request, outcome.observation());
            }
        }

        try {
            ToolResult result = tool.execute(arguments);
            return new ToolExecution(request, result);
        } catch (RuntimeException exception) {
            log.error("工具执行失败 chat.tool.failed name={} message={}", name, exception.getMessage());
            ToolResult failure = new ToolResult(
                    name,
                    false,
                    "tool execution failed: " + exception.getMessage(),
                    Map.of()
            );
            return new ToolExecution(request, failure);
        }
    }

    private ApprovalOutcome checkApproval(String toolName,
                                          String rawArguments,
                                          Map<String, Object> arguments,
                                          Map<String, Object> metadata) {
        String approvedId = stringValue(metadata, METADATA_APPROVED_ID);
        if (approvedId != null && pendingToolCallStore.consumeIfApproved(approvedId)) {
            log.info("HITL approval consumed chat.tool.approval.consumed name={} pendingId={}", toolName, approvedId);
            return ApprovalOutcome.proceed();
        }
        String sessionId = stringValue(metadata, METADATA_SESSION_ID);
        PendingToolCall pending = pendingToolCallStore.register(sessionId, toolName, rawArguments, arguments);
        log.info("HITL approval requested chat.tool.approval.requested name={} pendingId={} sessionId={}", toolName, pending.id(), sessionId);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("pendingId", pending.id());
        payload.put("status", "AWAITING_APPROVAL");
        payload.put("toolName", toolName);
        payload.put("arguments", arguments);
        ToolResult observation = new ToolResult(
                toolName,
                false,
                AWAITING_APPROVAL_SUMMARY_PREFIX + pending.id() + " — tool '" + toolName
                        + "' requires user approval before execution. Resend the request with metadata.approvedToolCallId="
                        + pending.id() + " after the user approves.",
                payload
        );
        return ApprovalOutcome.awaiting(pending, observation);
    }

    private static String stringValue(Map<String, Object> metadata, String key) {
        if (metadata == null) {
            return null;
        }
        Object value = metadata.get(key);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private Map<String, Object> parseArguments(String json) {
        if (json == null || json.isBlank()) {
            return new HashMap<>();
        }
        try {
            return OBJECT_MAPPER.readValue(json, ARG_MAP_TYPE);
        } catch (Exception exception) {
            log.warn("工具参数 JSON 解析失败 chat.tool.arg_parse_failed raw={} message={}", json, exception.getMessage());
            return new HashMap<>();
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }

    private static ThreadFactory namedThreadFactory(String prefix) {
        AtomicInteger counter = new AtomicInteger(1);
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + counter.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };
    }

    public record ToolExecution(ToolExecutionRequest request, ToolResult result) {
    }

    private record ApprovalOutcome(PendingToolCall pending, ToolResult observation) {
        static ApprovalOutcome proceed() {
            return new ApprovalOutcome(null, null);
        }

        static ApprovalOutcome awaiting(PendingToolCall pending, ToolResult observation) {
            return new ApprovalOutcome(pending, observation);
        }
    }
}
