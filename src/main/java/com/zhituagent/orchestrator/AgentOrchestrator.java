package com.zhituagent.orchestrator;

import com.zhituagent.rag.KnowledgeSnippet;
import com.zhituagent.rag.RagRetrievalResult;
import com.zhituagent.rag.RagRetriever;
import com.zhituagent.rag.RetrievalMode;
import com.zhituagent.rag.RetrievalRequestOptions;
import com.zhituagent.tool.ToolRegistry;
import com.zhituagent.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class AgentOrchestrator {

    private final RagRetriever ragRetriever;
    private final ToolRegistry toolRegistry;

    public AgentOrchestrator(RagRetriever ragRetriever, ToolRegistry toolRegistry) {
        this.ragRetriever = ragRetriever;
        this.toolRegistry = toolRegistry;
    }

    public RouteDecision decide(String userMessage) {
        return decide(userMessage, RetrievalRequestOptions.defaults());
    }

    public RouteDecision decide(String userMessage, RetrievalMode retrievalMode) {
        return decide(userMessage, RetrievalRequestOptions.withMode(retrievalMode));
    }

    public RouteDecision decide(String userMessage, RetrievalRequestOptions retrievalOptions) {
        if (looksLikeTimeQuestion(userMessage) && toolRegistry.find("time").isPresent()) {
            ToolResult toolResult = toolRegistry.find("time")
                    .orElseThrow()
                    .execute(Map.of("query", userMessage));
            return RouteDecision.tool("time", toolResult);
        }

        RagRetrievalResult retrievalResult = ragRetriever.retrieveDetailed(userMessage, 3, retrievalOptions);
        if (!retrievalResult.snippets().isEmpty()) {
            return RouteDecision.retrieval(retrievalResult);
        }

        return RouteDecision.direct();
    }

    private boolean looksLikeTimeQuestion(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return false;
        }

        String normalized = userMessage.toLowerCase();
        return normalized.contains("几点")
                || normalized.contains("星期几")
                || normalized.contains("周几")
                || normalized.contains("几号")
                || normalized.contains("几月几号")
                || normalized.contains("几月几日")
                || normalized.contains("日期")
                || normalized.contains("day of week")
                || normalized.contains("what day")
                || normalized.contains("time")
                || normalized.contains("date");
    }
}
