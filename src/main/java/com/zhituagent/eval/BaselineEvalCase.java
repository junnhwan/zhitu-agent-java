package com.zhituagent.eval;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
record BaselineEvalCase(
        String caseId,
        String type,
        String message,
        String expectedPath,
        boolean expectedRetrievalHit,
        boolean expectedToolUsed,
        boolean expectedSummaryPresentBeforeRun,
        String expectedContextStrategy,
        Integer expectedFactCountAtLeast,
        List<KnowledgeSeed> knowledgeEntries,
        List<HistoryTurn> historyTurns,
        Map<String, ModeExpectation> modeExpectations,
        List<String> relevantSourceIds,
        List<String> expectedAnswerKeywords,
        String notes
) {

    BaselineEvalCase {
        knowledgeEntries = knowledgeEntries == null ? List.of() : List.copyOf(knowledgeEntries);
        historyTurns = historyTurns == null ? List.of() : List.copyOf(historyTurns);
        modeExpectations = modeExpectations == null ? Map.of() : Map.copyOf(modeExpectations);
        relevantSourceIds = relevantSourceIds == null ? List.of() : List.copyOf(relevantSourceIds);
        expectedAnswerKeywords = expectedAnswerKeywords == null ? List.of() : List.copyOf(expectedAnswerKeywords);
    }

    ModeExpectation modeExpectationFor(String mode) {
        if (mode == null || mode.isBlank() || modeExpectations.isEmpty()) {
            return ModeExpectation.empty();
        }
        return modeExpectations.getOrDefault(mode, ModeExpectation.empty());
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

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ModeExpectation(
            String expectedPath,
            Boolean expectedRetrievalHit,
            Boolean expectedToolUsed,
            String expectedTopSource
    ) {

        static ModeExpectation empty() {
            return new ModeExpectation(null, null, null, null);
        }
    }
}
