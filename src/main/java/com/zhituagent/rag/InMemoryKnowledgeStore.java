package com.zhituagent.rag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class InMemoryKnowledgeStore implements KnowledgeStore {

    private final Map<String, KnowledgeChunk> chunksById = new LinkedHashMap<>();

    @Override
    public synchronized void addAll(List<KnowledgeChunk> chunks) {
        if (chunks == null) {
            return;
        }
        for (KnowledgeChunk chunk : chunks) {
            chunksById.put(chunk.chunkId(), chunk);
        }
    }

    @Override
    public synchronized List<KnowledgeSnippet> search(String query, int limit) {
        if (query == null || query.isBlank()) {
            return List.of();
        }

        String normalizedQuery = LexicalScoringUtils.normalize(query);
        List<KnowledgeSnippet> ranked = new ArrayList<>();
        for (KnowledgeChunk chunk : chunksById.values()) {
            double score = score(normalizedQuery, LexicalScoringUtils.normalize(chunk.content()));
            if (score > 0) {
                ranked.add(new KnowledgeSnippet(chunk.source(), chunk.chunkId(), score, chunk.content()));
            }
        }

        return ranked.stream()
                .sorted(Comparator.comparingDouble(KnowledgeSnippet::score).reversed())
                .limit(Math.max(1, limit))
                .toList();
    }

    @Override
    public synchronized List<KnowledgeSnippet> lexicalSearch(String query, int limit) {
        if (query == null || query.isBlank()) {
            return List.of();
        }

        List<KnowledgeSnippet> ranked = new ArrayList<>();
        for (KnowledgeChunk chunk : chunksById.values()) {
            double score = LexicalScoringUtils.scoreText(query, chunk.content());
            if (score > 0) {
                ranked.add(new KnowledgeSnippet(chunk.source(), chunk.chunkId(), score, chunk.content()));
            }
        }

        return ranked.stream()
                .sorted(Comparator.comparingDouble(KnowledgeSnippet::score).reversed())
                .limit(Math.max(1, limit))
                .toList();
    }

    public synchronized int size() {
        return chunksById.size();
    }

    private double score(String query, String content) {
        if (content.contains(query)) {
            return 1.0 + (double) query.length() / Math.max(1, content.length());
        }

        int overlap = 0;
        for (int i = 0; i < query.length(); i++) {
            String ch = query.substring(i, i + 1);
            if (!ch.isBlank() && content.contains(ch)) {
                overlap++;
            }
        }

        if (overlap < Math.min(2, query.length())) {
            return 0;
        }
        return (double) overlap / query.length();
    }

}
