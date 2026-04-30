package com.zhituagent.chat;

import com.zhituagent.api.ChatTraceFactory;
import com.zhituagent.api.dto.ChatResponse;
import com.zhituagent.api.dto.TraceInfo;
import com.zhituagent.config.AppProperties;
import com.zhituagent.context.ContextBundle;
import com.zhituagent.context.ContextManager;
import com.zhituagent.llm.LlmRuntime;
import com.zhituagent.memory.MemoryService;
import com.zhituagent.metrics.ChatMetricsRecorder;
import com.zhituagent.metrics.ToolMetricsRecorder;
import com.zhituagent.orchestrator.AgentOrchestrator;
import com.zhituagent.orchestrator.RouteDecision;
import com.zhituagent.rag.RetrievalMode;
import com.zhituagent.rag.RetrievalRequestOptions;
import com.zhituagent.session.SessionService;
import com.zhituagent.trace.TraceArchiveService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final LlmRuntime llmRuntime;
    private final SessionService sessionService;
    private final MemoryService memoryService;
    private final ContextManager contextManager;
    private final AgentOrchestrator agentOrchestrator;
    private final ChatTraceFactory chatTraceFactory;
    private final ChatMetricsRecorder chatMetricsRecorder;
    private final ToolMetricsRecorder toolMetricsRecorder;
    private final TraceArchiveService traceArchiveService;
    private final String systemPrompt;

    public ChatService(LlmRuntime llmRuntime,
                       SessionService sessionService,
                       MemoryService memoryService,
                       ContextManager contextManager,
                       AgentOrchestrator agentOrchestrator,
                       ChatTraceFactory chatTraceFactory,
                       ChatMetricsRecorder chatMetricsRecorder,
                       ToolMetricsRecorder toolMetricsRecorder,
                       TraceArchiveService traceArchiveService,
                       AppProperties appProperties,
                       ResourceLoader resourceLoader) throws IOException {
        this.llmRuntime = llmRuntime;
        this.sessionService = sessionService;
        this.memoryService = memoryService;
        this.contextManager = contextManager;
        this.agentOrchestrator = agentOrchestrator;
        this.chatTraceFactory = chatTraceFactory;
        this.chatMetricsRecorder = chatMetricsRecorder;
        this.toolMetricsRecorder = toolMetricsRecorder;
        this.traceArchiveService = traceArchiveService;
        Resource resource = resourceLoader.getResource(appProperties.getSystemPromptLocation());
        this.systemPrompt = resource.getContentAsString(StandardCharsets.UTF_8);
    }

    public ChatResponse chat(String sessionId,
                             String userId,
                             String message,
                             String requestId,
                             Map<String, Object> metadata) {
        return chat(sessionId, userId, message, requestId, metadata, RetrievalRequestOptions.defaults());
    }

    public ChatResponse chat(String sessionId,
                             String userId,
                             String message,
                             String requestId,
                             Map<String, Object> metadata,
                             RetrievalMode retrievalMode) {
        return chat(sessionId, userId, message, requestId, metadata, RetrievalRequestOptions.withMode(retrievalMode));
    }

    public ChatResponse chat(String sessionId,
                             String userId,
                             String message,
                             String requestId,
                             Map<String, Object> metadata,
                             RetrievalRequestOptions retrievalOptions) {
        long startNanos = System.nanoTime();
        Map<String, Object> safeMetadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        RetrievalRequestOptions safeOptions = retrievalOptions == null ? RetrievalRequestOptions.defaults() : retrievalOptions;
        RouteDecision routeDecision = null;
        ContextBundle contextBundle = null;
        try {
            sessionService.ensureSession(sessionId, userId);
            sessionService.appendMessage(sessionId, userId, "user", message);

            routeDecision = agentOrchestrator.decide(message, safeOptions);
            recordToolMetric(routeDecision);
            logRouteDecision(requestId, sessionId, routeDecision);

            contextBundle = contextManager.build(
                    systemPrompt,
                    memoryService.snapshot(sessionId),
                    message,
                    buildEvidenceBlock(routeDecision)
            );

            String answer = llmRuntime.generate(
                    systemPrompt,
                    contextBundle.modelMessages(),
                    safeMetadata
            );

            sessionService.appendMessage(sessionId, userId, "assistant", answer);
            long latencyMs = elapsedMillis(startNanos);
            TraceInfo traceInfo = chatTraceFactory.create(
                    routeDecision,
                    requestId,
                    latencyMs,
                    contextBundle,
                    answer
            );
            log.info(
                    "对话完成 chat.completed sessionId={} path={} retrievalHit={} toolUsed={} requestId={} answerLength={} latencyMs={}",
                    sessionId,
                    routeDecision.path(),
                    routeDecision.retrievalHit(),
                    routeDecision.toolUsed(),
                    requestId,
                    answer.length(),
                    latencyMs
            );
            chatMetricsRecorder.recordRequest(routeDecision.path(), false, true, latencyMs);
            traceArchiveService.archiveSuccess(
                    "chat.completed",
                    false,
                    sessionId,
                    userId,
                    requestId,
                    message,
                    answer,
                    traceInfo,
                    routeDecision
            );
            return new ChatResponse(sessionId, answer, traceInfo);
        } catch (Exception exception) {
            long latencyMs = elapsedMillis(startNanos);
            String path = routeDecision == null ? "direct-answer" : routeDecision.path();
            log.error(
                    "对话失败 chat.failed sessionId={} path={} requestId={} message={}",
                    sessionId,
                    path,
                    requestId,
                    exception.getMessage()
            );
            chatMetricsRecorder.recordRequest(path, false, false, latencyMs);
            traceArchiveService.archiveFailure(
                    "chat.failed",
                    false,
                    sessionId,
                    userId,
                    requestId,
                    message,
                    "",
                    exception.getMessage(),
                    latencyMs,
                    routeDecision,
                    contextBundle
            );
            throw exception;
        }
    }

    private String buildEvidenceBlock(RouteDecision routeDecision) {
        if (routeDecision == null) {
            return "";
        }
        if (routeDecision.toolUsed() && routeDecision.toolResult() != null) {
            return "TOOL RESULT: " + routeDecision.toolResult().summary();
        }
        if (routeDecision.retrievalHit() && !routeDecision.snippets().isEmpty()) {
            return routeDecision.snippets().stream()
                    .map(snippet -> "[" + snippet.source() + "] " + snippet.content())
                    .reduce((left, right) -> left + "\n---\n" + right)
                    .orElse("");
        }
        return "";
    }

    private void recordToolMetric(RouteDecision routeDecision) {
        if (routeDecision != null && routeDecision.toolUsed()) {
            toolMetricsRecorder.recordInvocation(routeDecision.toolName(), true);
        }
    }

    private void logRouteDecision(String requestId, String sessionId, RouteDecision routeDecision) {
        if (routeDecision == null) {
            log.info("路由决策已生成 chat.route.selected sessionId={} requestId={} path=direct-answer retrievalHit=false toolUsed=false snippetCount=0", sessionId, requestId);
            return;
        }
        log.info(
                "路由决策已生成 chat.route.selected sessionId={} requestId={} path={} retrievalHit={} toolUsed={} snippetCount={} topSource={} topScore={}",
                sessionId,
                requestId,
                routeDecision.path(),
                routeDecision.retrievalHit(),
                routeDecision.toolUsed(),
                routeDecision.snippets() == null ? 0 : routeDecision.snippets().size(),
                topSource(routeDecision),
                topScore(routeDecision)
        );
    }

    private String topSource(RouteDecision routeDecision) {
        if (routeDecision == null || routeDecision.snippets() == null || routeDecision.snippets().isEmpty()) {
            return "-";
        }
        return routeDecision.snippets().getFirst().source();
    }

    private String topScore(RouteDecision routeDecision) {
        if (routeDecision == null || routeDecision.snippets() == null || routeDecision.snippets().isEmpty()) {
            return "-";
        }
        return String.format("%.4f", routeDecision.snippets().getFirst().score());
    }

    private long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
