package com.zhituagent.rag;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnowledgeStoreIdsTest {

    @Test
    void shouldConvertArbitraryChunkIdToStableUuid() {
        String first = KnowledgeStoreIds.toEmbeddingId("pgvector-live-test.md#1");
        String second = KnowledgeStoreIds.toEmbeddingId("pgvector-live-test.md#1");

        assertThat(first).isEqualTo(second);
        assertThatCode(() -> UUID.fromString(first)).doesNotThrowAnyException();
    }

    @Test
    void shouldComputeStableChunkIdFromSourceAndContent() {
        String first = KnowledgeStoreIds.computeChunkId("notes.md", "Q: 第一版重点\nA: 主链路清晰。");
        String second = KnowledgeStoreIds.computeChunkId("notes.md", "Q: 第一版重点\nA: 主链路清晰。");

        assertThat(first).isEqualTo(second);
        assertThat(first).startsWith("notes.md#");
        assertThat(first).hasSize("notes.md#".length() + 16);
    }

    @Test
    void shouldDifferentiateBySourceWhenContentMatches() {
        String fromA = KnowledgeStoreIds.computeChunkId("doc-a.md", "shared content");
        String fromB = KnowledgeStoreIds.computeChunkId("doc-b.md", "shared content");

        assertThat(fromA).isNotEqualTo(fromB);
    }

    @Test
    void shouldDifferentiateByContentWhenSourceMatches() {
        String first = KnowledgeStoreIds.computeChunkId("doc.md", "first chunk");
        String second = KnowledgeStoreIds.computeChunkId("doc.md", "second chunk");

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void shouldRejectBlankSource() {
        assertThatThrownBy(() -> KnowledgeStoreIds.computeChunkId("", "content"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> KnowledgeStoreIds.computeChunkId(null, "content"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNullContent() {
        assertThatThrownBy(() -> KnowledgeStoreIds.computeChunkId("doc.md", null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
