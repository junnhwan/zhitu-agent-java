package com.zhituagent.orchestrator;

import com.zhituagent.config.AppProperties;
import com.zhituagent.llm.ChatTurnResult;
import com.zhituagent.llm.LlmRuntime;
import com.zhituagent.rag.RagRetrievalResult;
import com.zhituagent.rag.RagRetriever;
import com.zhituagent.rag.RetrievalMode;
import com.zhituagent.rag.RetrievalRequestOptions;
import com.zhituagent.rag.SelfRagOrchestrator;
import com.zhituagent.tool.ToolRegistry;
import com.zhituagent.tool.ToolResult;
import dev.langchain4j.agent.tool.ToolSpecification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class AgentOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(AgentOrchestrator.class);

    private final RagRetriever ragRetriever;
    private final SelfRagOrchestrator selfRagOrchestrator;
    private final ToolRegistry toolRegistry;
    private final LlmRuntime llmRuntime;
    private final ToolCallExecutor toolCallExecutor;
    private final String systemPrompt;

    @Autowired
    public AgentOrchestrator(RagRetriever ragRetriever,
                             SelfRagOrchestrator selfRagOrchestrator,
                             ToolRegistry toolRegistry,
                             LlmRuntime llmRuntime,
                             ToolCallExecutor toolCallExecutor,
                             AppProperties appProperties,
                             ResourceLoader resourceLoader) throws IOException {
        this.ragRetriever = ragRetriever;
        this.selfRagOrchestrator = selfRagOrchestrator;
        this.toolRegistry = toolRegistry;
        this.llmRuntime = llmRuntime;
        this.toolCallExecutor = toolCallExecutor;
        Resource resource = resourceLoader.getResource(appProperties.getSystemPromptLocation());
        this.systemPrompt = resource.getContentAsString(StandardCharsets.UTF_8);
    }

    AgentOrchestrator(RagRetriever ragRetriever,
                      ToolRegistry toolRegistry,
                      LlmRuntime llmRuntime,
                      ToolCallExecutor toolCallExecutor,
                      String systemPrompt) {
        this(ragRetriever, null, toolRegistry, llmRuntime, toolCallExecutor, systemPrompt);
    }

    AgentOrchestrator(RagRetriever ragRetriever,
                      SelfRagOrchestrator selfRagOrchestrator,
                      ToolRegistry toolRegistry,
                      LlmRuntime llmRuntime,
                      ToolCallExecutor toolCallExecutor,
                      String systemPrompt) {
        this.ragRetriever = ragRetriever;
        this.selfRagOrchestrator = selfRagOrchestrator;
        this.toolRegistry = toolRegistry;
        this.llmRuntime = llmRuntime;
        this.toolCallExecutor = toolCallExecutor;
        this.systemPrompt = systemPrompt;
    }

    public RouteDecision decide(String userMessage) {
        return decide(userMessage, RetrievalRequestOptions.defaults(), Map.of());
    }

    public RouteDecision decide(String userMessage, RetrievalMode retrievalMode) {
        return decide(userMessage, RetrievalRequestOptions.withMode(retrievalMode), Map.of());
    }

    public RouteDecision decide(String userMessage, RetrievalRequestOptions retrievalOptions) {
        return decide(userMessage, retrievalOptions, Map.of());
    }

    public RouteDecision decide(String userMessage,
                                RetrievalRequestOptions retrievalOptions,
                                Map<String, Object> sessionMetadata) {
        Map<String, Object> safeMetadata = sessionMetadata == null ? Map.of() : sessionMetadata;

        RagRetrievalResult retrievalResult = retrieveWithOptionalSelfRag(userMessage, retrievalOptions);
        if (!retrievalResult.snippets().isEmpty()) {
            return RouteDecision.retrieval(retrievalResult);
        }

        List<ToolSpecification> specs = toolRegistry.specifications();
        if (specs.isEmpty()) {
            return RouteDecision.direct();
        }

        ChatTurnResult turn = llmRuntime.generateWithTools(
                systemPrompt,
                List.of("USER: " + userMessage),
                specs,
                Map.of("phase", "tool-selection")
        );
        if (!turn.hasToolCalls()) {
            return RouteDecision.direct();
        }

        List<ToolCallExecutor.ToolExecution> executions = toolCallExecutor.executeAll(turn.toolCalls(), safeMetadata);
        if (executions.isEmpty()) {
            return RouteDecision.direct();
        }

        ToolResult aggregate = aggregate(executions);
        String firstName = executions.get(0).result().toolName();
        return RouteDecision.tool(firstName, aggregate);
    }

    private RagRetrievalResult retrieveWithOptionalSelfRag(String userMessage, RetrievalRequestOptions retrievalOptions) {
        if (selfRagOrchestrator != null && selfRagOrchestrator.isEnabled()) {
            return selfRagOrchestrator.retrieveWithRefinement(userMessage, 3, retrievalOptions);
        }
        return ragRetriever.retrieveDetailed(userMessage, 3, retrievalOptions);
    }

    private ToolResult aggregate(List<ToolCallExecutor.ToolExecution> executions) {
        if (executions.size() == 1) {
            return executions.get(0).result();
        }
        boolean allOk = executions.stream().allMatch(execution -> execution.result().success());
        StringBuilder summary = new StringBuilder();
        Map<String, Object> payload = new LinkedHashMap<>();
        for (ToolCallExecutor.ToolExecution execution : executions) {
            ToolResult result = execution.result();
            summary.append("[").append(result.toolName()).append("] ").append(result.summary()).append("\n");
            payload.put(result.toolName(), Map.of(
                    "success", result.success(),
                    "summary", result.summary(),
                    "payload", result.payload()
            ));
        }
        log.info("多工具并行执行 chat.tool.parallel toolCount={} allSuccess={}", executions.size(), allOk);
        String trimmedSummary = summary.toString().trim();
        return new ToolResult("multi-tool", allOk, trimmedSummary, payload);
    }
}
