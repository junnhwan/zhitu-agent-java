package com.zhituagent.api;

import com.zhituagent.api.dto.TraceInfo;
import com.zhituagent.orchestrator.RouteDecision;
import com.zhituagent.rag.KnowledgeSnippet;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class ChatTraceFactory {

    private static final String DEFAULT_PATH = "direct-answer";
    private static final String DEFAULT_RETRIEVAL_MODE = "none";
    private static final String CONTEXT_STRATEGY = "recent-summary";
    private static final String DEFAULT_RERANK_MODEL = "";

    public TraceInfo create(RouteDecision routeDecision,
                            String requestId,
                            long latencyMs,
                            List<String> inputMessages,
                            String outputText) {
        List<KnowledgeSnippet> snippets = routeDecision == null || routeDecision.snippets() == null
                ? List.of()
                : routeDecision.snippets();
        KnowledgeSnippet topSnippet = snippets.isEmpty() ? null : snippets.getFirst();
        long inputTokenEstimate = estimateMessages(inputMessages);
        long outputTokenEstimate = estimateText(outputText);

        return new TraceInfo(
                routeDecision == null ? DEFAULT_PATH : routeDecision.path(),
                routeDecision != null && routeDecision.retrievalHit(),
                routeDecision != null && routeDecision.toolUsed(),
                resolveRetrievalMode(routeDecision),
                CONTEXT_STRATEGY,
                requestId == null || requestId.isBlank() ? "-" : requestId,
                Math.max(0, latencyMs),
                snippets.size(),
                topSnippet == null ? "" : topSnippet.source(),
                topSnippet == null ? 0.0 : topSnippet.score(),
                routeDecision == null ? 0 : Math.max(0, routeDecision.retrievalCandidateCount()),
                routeDecision == null || routeDecision.rerankModel() == null ? DEFAULT_RERANK_MODEL : routeDecision.rerankModel(),
                routeDecision == null ? 0.0 : routeDecision.rerankTopScore(),
                inputTokenEstimate,
                outputTokenEstimate
        );
    }

    private String resolveRetrievalMode(RouteDecision routeDecision) {
        if (routeDecision == null || routeDecision.retrievalMode() == null || routeDecision.retrievalMode().isBlank()) {
            return DEFAULT_RETRIEVAL_MODE;
        }
        return routeDecision.retrievalMode();
    }

    private long estimateMessages(List<String> inputMessages) {
        if (inputMessages == null || inputMessages.isEmpty()) {
            return 0;
        }
        List<String> safeMessages = new ArrayList<>(inputMessages);
        return safeMessages.stream()
                .mapToLong(this::estimateText)
                .sum();
    }

    private long estimateText(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }

        long cjkCount = text.codePoints()
                .filter(codePoint -> !Character.isWhitespace(codePoint))
                .filter(this::isCjk)
                .count();

        long otherCount = text.codePoints()
                .filter(codePoint -> !Character.isWhitespace(codePoint))
                .filter(codePoint -> !isCjk(codePoint))
                .count();

        return cjkCount + Math.max(0, Math.round(Math.ceil(otherCount / 4.0)));
    }

    private boolean isCjk(int codePoint) {
        Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
        return script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL;
    }
}
