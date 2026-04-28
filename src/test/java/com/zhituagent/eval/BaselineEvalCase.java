package com.zhituagent.eval;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
record BaselineEvalCase(
        String caseId,
        String type,
        String message,
        String expectedPath,
        boolean expectedRetrievalHit,
        boolean expectedToolUsed,
        boolean expectedSummaryPresentBeforeRun,
        List<KnowledgeSeed> knowledgeEntries,
        List<HistoryTurn> historyTurns,
        String notes
) {

    BaselineEvalCase {
        knowledgeEntries = knowledgeEntries == null ? List.of() : List.copyOf(knowledgeEntries);
        historyTurns = historyTurns == null ? List.of() : List.copyOf(historyTurns);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record KnowledgeSeed(
            String question,
            String answer,
            String sourceName
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record HistoryTurn(
            String user,
            String assistant
    ) {
    }
}
