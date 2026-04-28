package com.zhituagent.orchestrator;

import com.zhituagent.rag.KnowledgeSnippet;
import com.zhituagent.tool.ToolResult;

import java.util.List;

public record RouteDecision(
        String path,
        boolean retrievalHit,
        boolean toolUsed,
        String toolName,
        ToolResult toolResult,
        List<KnowledgeSnippet> snippets,
        String retrievalMode,
        int retrievalCandidateCount,
        String rerankModel,
        double rerankTopScore
) {

    public static RouteDecision direct() {
        return new RouteDecision(
                "direct-answer",
                false,
                false,
                null,
                null,
                List.of(),
                "none",
                0,
                "",
                0.0
        );
    }

    public static RouteDecision tool(String toolName, ToolResult toolResult) {
        return new RouteDecision(
                "tool-then-answer",
                false,
                true,
                toolName,
                toolResult,
                List.of(),
                "none",
                0,
                "",
                0.0
        );
    }

    public static RouteDecision denseRetrieval(List<KnowledgeSnippet> snippets) {
        List<KnowledgeSnippet> safeSnippets = snippets == null ? List.of() : List.copyOf(snippets);
        return new RouteDecision(
                "retrieve-then-answer",
                !safeSnippets.isEmpty(),
                false,
                null,
                null,
                safeSnippets,
                safeSnippets.isEmpty() ? "none" : "dense",
                safeSnippets.size(),
                "",
                0.0
        );
    }
}
