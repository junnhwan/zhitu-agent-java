package com.zhituagent.trace;

import java.util.List;

public record TraceArchiveEntry(
        String timestamp,
        String event,
        boolean stream,
        String sessionId,
        String userId,
        String requestId,
        String userMessage,
        String answerPreview,
        String errorMessage,
        String path,
        boolean retrievalHit,
        boolean toolUsed,
        String toolName,
        String retrievalMode,
        String contextStrategy,
        int snippetCount,
        int retrievalCandidateCount,
        String topSource,
        double topScore,
        String rerankModel,
        double rerankTopScore,
        int factCount,
        long inputTokenEstimate,
        long outputTokenEstimate,
        long latencyMs,
        List<SnippetTraceEntry> snippets
) {

    public record SnippetTraceEntry(
            String source,
            String chunkId,
            double score,
            double denseScore,
            double rerankScore,
            String contentPreview
    ) {
    }
}
