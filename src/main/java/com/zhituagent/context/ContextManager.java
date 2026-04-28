package com.zhituagent.context;

import com.zhituagent.memory.MemorySnapshot;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Component
public class ContextManager {

    private static final int DEFAULT_MAX_INPUT_TOKENS = 640;
    private static final int DEFAULT_MAX_SUMMARY_TOKENS = 180;
    private static final int DEFAULT_MAX_FACTS_TOKENS = 120;
    private static final int DEFAULT_MAX_EVIDENCE_TOKENS = 240;
    private static final int DEFAULT_MAX_MESSAGE_TOKENS = 120;
    private static final String BASE_STRATEGY = "recent-summary";
    private static final String FACTS_SUFFIX = "-facts";
    private static final String BUDGETED_SUFFIX = "-budgeted";

    private final TokenEstimator tokenEstimator;
    private final int maxInputTokens;
    private final int maxSummaryTokens;
    private final int maxFactsTokens;
    private final int maxEvidenceTokens;
    private final int maxMessageTokens;

    public ContextManager() {
        this(
                new TokenEstimator(),
                DEFAULT_MAX_INPUT_TOKENS,
                DEFAULT_MAX_SUMMARY_TOKENS,
                DEFAULT_MAX_FACTS_TOKENS,
                DEFAULT_MAX_EVIDENCE_TOKENS,
                DEFAULT_MAX_MESSAGE_TOKENS
        );
    }

    ContextManager(int maxInputTokens,
                   int maxSummaryTokens,
                   int maxFactsTokens,
                   int maxEvidenceTokens,
                   int maxMessageTokens) {
        this(
                new TokenEstimator(),
                maxInputTokens,
                maxSummaryTokens,
                maxFactsTokens,
                maxEvidenceTokens,
                maxMessageTokens
        );
    }

    ContextManager(TokenEstimator tokenEstimator,
                   int maxInputTokens,
                   int maxSummaryTokens,
                   int maxFactsTokens,
                   int maxEvidenceTokens,
                   int maxMessageTokens) {
        this.tokenEstimator = tokenEstimator;
        this.maxInputTokens = maxInputTokens;
        this.maxSummaryTokens = maxSummaryTokens;
        this.maxFactsTokens = maxFactsTokens;
        this.maxEvidenceTokens = maxEvidenceTokens;
        this.maxMessageTokens = maxMessageTokens;
    }

    public ContextBundle build(String systemPrompt,
                               MemorySnapshot memorySnapshot,
                               String currentMessage,
                               String ragEvidence) {
        BudgetedContext budgetedContext = budgetContext(systemPrompt, memorySnapshot, currentMessage, ragEvidence);
        List<String> modelMessages = buildModelMessages(
                systemPrompt,
                budgetedContext.summary(),
                budgetedContext.facts(),
                budgetedContext.recentMessages(),
                budgetedContext.ragEvidence(),
                currentMessage
        );

        return new ContextBundle(
                systemPrompt,
                budgetedContext.summary(),
                budgetedContext.recentMessages(),
                budgetedContext.facts(),
                currentMessage,
                List.copyOf(modelMessages),
                budgetedContext.contextStrategy()
        );
    }

    private BudgetedContext budgetContext(String systemPrompt,
                                          MemorySnapshot memorySnapshot,
                                          String currentMessage,
                                          String ragEvidence) {
        String summary = trimToTokenLimit(memorySnapshot.summary(), maxSummaryTokens);
        String evidence = trimToTokenLimit(ragEvidence, maxEvidenceTokens);
        List<String> facts = limitFacts(memorySnapshot.facts());
        List<com.zhituagent.memory.ChatMessageRecord> recentMessages = limitRecentMessages(memorySnapshot.recentMessages());

        boolean budgeted = !safeEquals(summary, memorySnapshot.summary())
                || !safeEquals(evidence, ragEvidence)
                || facts.size() != safeList(memorySnapshot.facts()).size()
                || recentMessages.size() != safeList(memorySnapshot.recentMessages()).size();

        List<String> modelMessages = buildModelMessages(systemPrompt, summary, facts, recentMessages, evidence, currentMessage);

        while (tokenEstimator.estimateMessages(modelMessages) > maxInputTokens && !recentMessages.isEmpty()) {
            recentMessages = new ArrayList<>(recentMessages.subList(1, recentMessages.size()));
            budgeted = true;
            modelMessages = buildModelMessages(systemPrompt, summary, facts, recentMessages, evidence, currentMessage);
        }

        while (tokenEstimator.estimateMessages(modelMessages) > maxInputTokens && facts.size() > 1) {
            facts = new ArrayList<>(facts.subList(1, facts.size()));
            budgeted = true;
            modelMessages = buildModelMessages(systemPrompt, summary, facts, recentMessages, evidence, currentMessage);
        }

        if (tokenEstimator.estimateMessages(modelMessages) > maxInputTokens && summary != null && !summary.isBlank()) {
            summary = "";
            budgeted = true;
            modelMessages = buildModelMessages(systemPrompt, summary, facts, recentMessages, evidence, currentMessage);
        }

        if (tokenEstimator.estimateMessages(modelMessages) > maxInputTokens && evidence != null && !evidence.isBlank()) {
            evidence = trimToTokenLimit(evidence, Math.max(24, maxEvidenceTokens / 2));
            budgeted = true;
            modelMessages = buildModelMessages(systemPrompt, summary, facts, recentMessages, evidence, currentMessage);
        }

        return new BudgetedContext(
                summary,
                List.copyOf(recentMessages),
                List.copyOf(facts),
                evidence,
                resolveContextStrategy(facts, budgeted)
        );
    }

    private List<String> buildModelMessages(String systemPrompt,
                                            String summary,
                                            List<String> facts,
                                            List<com.zhituagent.memory.ChatMessageRecord> recentMessages,
                                            String ragEvidence,
                                            String currentMessage) {
        List<String> modelMessages = new ArrayList<>();
        modelMessages.add("SYSTEM: " + systemPrompt);

        if (summary != null && !summary.isBlank()) {
            modelMessages.add("SUMMARY: " + summary);
        }

        if (facts != null && !facts.isEmpty()) {
            modelMessages.add("FACTS: " + String.join(" | ", facts));
        }

        safeList(recentMessages).forEach(message ->
                modelMessages.add(message.role().toUpperCase() + ": " + message.content())
        );

        if (ragEvidence != null && !ragEvidence.isBlank()) {
            modelMessages.add("EVIDENCE: " + ragEvidence);
        }

        modelMessages.add("USER: " + currentMessage);
        return modelMessages;
    }

    private List<String> limitFacts(List<String> facts) {
        if (facts == null || facts.isEmpty()) {
            return List.of();
        }

        List<String> limitedFacts = new ArrayList<>();
        long usedTokens = 0;
        for (String fact : facts) {
            String normalizedFact = trimToTokenLimit(fact, Math.min(maxMessageTokens, maxFactsTokens));
            if (normalizedFact.isBlank()) {
                continue;
            }

            long factTokens = tokenEstimator.estimateText(normalizedFact);
            if (!limitedFacts.isEmpty() && usedTokens + factTokens > maxFactsTokens) {
                break;
            }

            limitedFacts.add(normalizedFact);
            usedTokens += factTokens;
        }
        return List.copyOf(limitedFacts);
    }

    private List<com.zhituagent.memory.ChatMessageRecord> limitRecentMessages(List<com.zhituagent.memory.ChatMessageRecord> recentMessages) {
        if (recentMessages == null || recentMessages.isEmpty()) {
            return List.of();
        }
        List<com.zhituagent.memory.ChatMessageRecord> limited = new ArrayList<>();
        for (com.zhituagent.memory.ChatMessageRecord message : recentMessages) {
            String normalizedContent = trimToTokenLimit(message.content(), maxMessageTokens);
            limited.add(new com.zhituagent.memory.ChatMessageRecord(message.role(), normalizedContent, message.timestamp()));
        }
        return List.copyOf(limited);
    }

    private String trimToTokenLimit(String text, int tokenLimit) {
        if (text == null || text.isBlank() || tokenLimit <= 0) {
            return "";
        }
        if (tokenEstimator.estimateText(text) <= tokenLimit) {
            return text;
        }

        String trimmed = text.trim();
        int low = 0;
        int high = trimmed.length();
        String best = "";
        while (low <= high) {
            int mid = (low + high) / 2;
            String candidate = trimmed.substring(0, mid).trim();
            if (!candidate.isEmpty()) {
                candidate = candidate + "...";
            }
            long estimatedTokens = tokenEstimator.estimateText(candidate);
            if (estimatedTokens <= tokenLimit) {
                best = candidate;
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        return best;
    }

    private String resolveContextStrategy(List<String> facts, boolean budgeted) {
        String strategy = BASE_STRATEGY;
        if (facts != null && !facts.isEmpty()) {
            strategy += FACTS_SUFFIX;
        }
        if (budgeted) {
            strategy += BUDGETED_SUFFIX;
        }
        return strategy;
    }

    private <T> List<T> safeList(List<T> values) {
        return values == null ? Collections.emptyList() : values;
    }

    private boolean safeEquals(String left, String right) {
        return (left == null || left.isBlank())
                ? right == null || right.isBlank()
                : left.equals(right);
    }

    private record BudgetedContext(
            String summary,
            List<com.zhituagent.memory.ChatMessageRecord> recentMessages,
            List<String> facts,
            String ragEvidence,
            String contextStrategy
    ) {
    }
}
