package com.zhituagent.memory;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class FactExtractor {

    private static final int MAX_FACTS = 6;
    private static final List<String> INTENT_KEYWORDS = List.of(
            "想问",
            "请问",
            "问一下",
            "帮我",
            "告诉我",
            "介绍一下",
            "总结一下",
            "怎么看",
            "怎么做",
            "是什么"
    );

    public List<String> extract(List<ChatMessageRecord> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }

        Set<String> facts = new LinkedHashSet<>();
        for (ChatMessageRecord message : messages) {
            if (message == null || !"user".equalsIgnoreCase(message.role()) || message.content() == null || message.content().isBlank()) {
                continue;
            }

            for (String clause : splitClauses(message.content())) {
                if (isStableFact(clause)) {
                    facts.add(clause);
                    if (facts.size() >= MAX_FACTS) {
                        return List.copyOf(facts);
                    }
                }
            }
        }
        return List.copyOf(facts);
    }

    private List<String> splitClauses(String content) {
        return java.util.Arrays.stream(content.split("[。！？!?；;，,\\r\\n]+"))
                .map(this::normalize)
                .filter(clause -> !clause.isBlank())
                .toList();
    }

    private String normalize(String clause) {
        return clause.trim().replaceAll("\\s+", " ");
    }

    private boolean isStableFact(String clause) {
        if (clause.length() < 3 || clause.length() > 80) {
            return false;
        }
        if (clause.endsWith("?") || clause.endsWith("？") || clause.endsWith("吗")) {
            return false;
        }
        if (containsIntentKeyword(clause)) {
            return false;
        }

        String lowerCaseClause = clause.toLowerCase(Locale.ROOT);
        return clause.startsWith("我叫")
                || clause.startsWith("我是")
                || clause.startsWith("我在")
                || clause.startsWith("我做")
                || clause.startsWith("我负责")
                || clause.startsWith("我来自")
                || clause.startsWith("我住在")
                || clause.startsWith("我目前在")
                || clause.startsWith("我现在在")
                || clause.startsWith("我的目标是")
                || clause.startsWith("我正在")
                || lowerCaseClause.startsWith("my name is")
                || lowerCaseClause.startsWith("i am")
                || lowerCaseClause.startsWith("i'm")
                || lowerCaseClause.startsWith("i work")
                || lowerCaseClause.startsWith("i live");
    }

    private boolean containsIntentKeyword(String clause) {
        return INTENT_KEYWORDS.stream().anyMatch(clause::contains);
    }
}
