package com.zhituagent.eval;

import java.util.List;

record BaselineEvalResult(
        String fixtureName,
        String generatedAt,
        int totalCases,
        int passedCases,
        double routeAccuracy,
        double retrievalHitRate,
        double toolHitRate,
        double summaryExpectationHitRate,
        double averageLatencyMs,
        double p50LatencyMs,
        double p90LatencyMs,
        double averageInputTokenEstimate,
        double averageOutputTokenEstimate,
        List<CaseResult> results
) {

    record CaseResult(
            String caseId,
            String type,
            String sessionId,
            String expectedPath,
            String actualPath,
            boolean routeMatched,
            boolean expectedRetrievalHit,
            boolean actualRetrievalHit,
            boolean retrievalMatched,
            boolean expectedToolUsed,
            boolean actualToolUsed,
            boolean toolMatched,
            boolean expectedSummaryPresentBeforeRun,
            boolean summaryPresentBeforeRun,
            boolean summaryMatched,
            String retrievalMode,
            String contextStrategy,
            int snippetCount,
            int retrievalCandidateCount,
            String topSource,
            double topScore,
            String rerankModel,
            double rerankTopScore,
            long latencyMs,
            long inputTokenEstimate,
            long outputTokenEstimate,
            String answerPreview
    ) {
    }
}
