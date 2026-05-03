package com.zhituagent.rag;

import java.util.List;

public interface KnowledgeStore {

    void addAll(List<KnowledgeChunk> chunks);

    List<KnowledgeSnippet> search(String query, int limit);

    default List<KnowledgeSnippet> lexicalSearch(String query, int limit) {
        return List.of();
    }

    /**
     * Native hybrid search in a single round-trip if the backing store supports it
     * (e.g. Elasticsearch KNN + match + rescore). Default implementation falls back
     * to dense-only {@link #search}; callers (e.g. {@code RagRetriever}) are then
     * responsible for fusing dense + lexical themselves.
     */
    default List<KnowledgeSnippet> hybridSearch(String query, int limit) {
        return search(query, limit);
    }

    /**
     * Whether {@link #hybridSearch} is implemented natively (single backend call
     * that already fuses dense + sparse). When false, callers should run
     * {@link #search} + {@link #lexicalSearch} separately and fuse themselves.
     */
    default boolean supportsNativeHybrid() {
        return false;
    }
}
