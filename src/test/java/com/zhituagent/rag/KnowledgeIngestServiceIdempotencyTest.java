package com.zhituagent.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeIngestServiceIdempotencyTest {

    @Test
    void shouldUpsertOnRepeatedIngestOfSameQuestionAnswer() {
        InMemoryKnowledgeStore store = new InMemoryKnowledgeStore();
        KnowledgeIngestService ingestService = new KnowledgeIngestService(new DocumentSplitter(), store);

        ingestService.ingest("第一版重点是什么？", "第一版重点是主链路清晰。", "notes.md");
        int sizeAfterFirst = store.size();
        assertThat(sizeAfterFirst).isGreaterThan(0);

        ingestService.ingest("第一版重点是什么？", "第一版重点是主链路清晰。", "notes.md");

        assertThat(store.size())
                .as("identical (source, q, a) should overwrite via stable chunkId, not duplicate")
                .isEqualTo(sizeAfterFirst);
    }

    @Test
    void shouldGenerateDistinctChunksForDifferentSources() {
        InMemoryKnowledgeStore store = new InMemoryKnowledgeStore();
        KnowledgeIngestService ingestService = new KnowledgeIngestService(new DocumentSplitter(), store);

        ingestService.ingest("题目", "答案", "doc-a.md");
        ingestService.ingest("题目", "答案", "doc-b.md");

        assertThat(store.size()).isEqualTo(2);
    }

    @Test
    void shouldSurviveRestartWithStableIds() {
        // Simulate a "restart" by spawning a fresh ingest service backed by the same store.
        InMemoryKnowledgeStore store = new InMemoryKnowledgeStore();
        new KnowledgeIngestService(new DocumentSplitter(), store)
                .ingest("题目", "答案", "doc.md");
        int initialSize = store.size();

        // Old behaviour with auto-incrementing counter would now write doc.md#2 etc.
        // With sha256-based ids, the second service computes the same id and upserts.
        new KnowledgeIngestService(new DocumentSplitter(), store)
                .ingest("题目", "答案", "doc.md");

        assertThat(store.size())
                .as("restart should not duplicate chunks because chunkId is content-derived, not counter-derived")
                .isEqualTo(initialSize);
    }

    @Test
    void shouldUseSourceQualifiedChunkIdInSnippet() {
        InMemoryKnowledgeStore store = new InMemoryKnowledgeStore();
        KnowledgeIngestService ingestService = new KnowledgeIngestService(new DocumentSplitter(), store);
        ingestService.ingest("第一版重点是什么？", "第一版重点是主链路清晰。", "notes.md");

        List<KnowledgeSnippet> snippets = ingestService.search("第一版重点", 3);

        assertThat(snippets).isNotEmpty();
        assertThat(snippets.getFirst().chunkId()).startsWith("notes.md#");
    }
}
