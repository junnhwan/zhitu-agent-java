package com.zhituagent.api.dto;

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
        long inputTokenEstimate,
        long outputTokenEstimate
) {
}
