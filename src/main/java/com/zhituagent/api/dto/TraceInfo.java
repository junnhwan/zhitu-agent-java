package com.zhituagent.api.dto;

import java.util.List;

public record TraceInfo(
        String path,
        boolean retrievalHit,
        boolean toolUsed,
        String retrievalMode,
        String contextStrategy,
        String requestId,
        long latencyMs,
        int snippetCount,
        String topSource,
        double topScore,
        int retrievalCandidateCount,
        String rerankModel,
        double rerankTopScore,
        int factCount,
        long inputTokenEstimate,
        long outputTokenEstimate,
        List<String> retrievedSources
) {

    public TraceInfo {
        retrievedSources = retrievedSources == null ? List.of() : List.copyOf(retrievedSources);
    }
}
