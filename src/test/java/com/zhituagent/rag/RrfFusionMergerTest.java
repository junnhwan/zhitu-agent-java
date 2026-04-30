package com.zhituagent.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RrfFusionMergerTest {

    @Test
    void shouldRankItemAppearingInBothListsAboveSingletons() {
        RrfFusionMerger merger = new RrfFusionMerger();
        List<KnowledgeSnippet> dense = List.of(
                new KnowledgeSnippet("src", "chunk-A", 0.95, "alpha"),
                new KnowledgeSnippet("src", "chunk-B", 0.80, "beta")
        );
        List<KnowledgeSnippet> lexical = List.of(
                new KnowledgeSnippet("src", "chunk-B", 1.0, "beta-lex"),
                new KnowledgeSnippet("src", "chunk-C", 0.9, "gamma")
        );

        List<RetrievalCandidate> merged = merger.merge(dense, lexical, 5);

        assertThat(merged).extracting(RetrievalCandidate::chunkId).startsWith("chunk-B");
        // chunk-B appears at rank 1 in dense AND rank 0 in lexical → 1/(60+1) + 1/(60+0)
        // chunk-A appears at rank 0 in dense only → 1/(60+0)
        assertThat(merged.getFirst().score())
                .isGreaterThan(merged.stream()
                        .filter(c -> c.chunkId().equals("chunk-A"))
                        .findFirst().orElseThrow().score());
    }

    @Test
    void shouldPreserveBothChannelRawScoresOnMergedCandidate() {
        RrfFusionMerger merger = new RrfFusionMerger();
        List<KnowledgeSnippet> dense = List.of(new KnowledgeSnippet("s", "X", 0.42, "x"));
        List<KnowledgeSnippet> lexical = List.of(new KnowledgeSnippet("s", "X", 0.88, "x-lex"));

        List<RetrievalCandidate> merged = merger.merge(dense, lexical, 5);

        assertThat(merged).hasSize(1);
        assertThat(merged.getFirst().denseScore()).isEqualTo(0.42);
        assertThat(merged.getFirst().lexicalScore()).isEqualTo(0.88);
    }

    @Test
    void shouldHandleEmptyLexicalSide() {
        RrfFusionMerger merger = new RrfFusionMerger();
        List<KnowledgeSnippet> dense = List.of(
                new KnowledgeSnippet("s", "X", 0.9, "x"),
                new KnowledgeSnippet("s", "Y", 0.5, "y")
        );

        List<RetrievalCandidate> merged = merger.merge(dense, List.of(), 5);

        assertThat(merged).extracting(RetrievalCandidate::chunkId).containsExactly("X", "Y");
    }

    @Test
    void shouldRespectLimit() {
        RrfFusionMerger merger = new RrfFusionMerger();
        List<KnowledgeSnippet> dense = List.of(
                new KnowledgeSnippet("s", "A", 0.9, "a"),
                new KnowledgeSnippet("s", "B", 0.8, "b"),
                new KnowledgeSnippet("s", "C", 0.7, "c")
        );

        List<RetrievalCandidate> merged = merger.merge(dense, List.of(), 2);

        assertThat(merged).hasSize(2);
    }

    @Test
    void shouldRecoverChunkRankedLowOnDenseButHighOnLexical() {
        // Classic RRF win: chunk that's rank 4 dense + rank 0 lexical beats chunk that's rank 0 dense.
        // 1/64 + 1/60 = 0.01563 + 0.01667 = 0.03229 vs 1/60 = 0.01667
        RrfFusionMerger merger = new RrfFusionMerger();
        List<KnowledgeSnippet> dense = List.of(
                new KnowledgeSnippet("s", "TOP-DENSE", 0.99, "popular"),
                new KnowledgeSnippet("s", "X", 0.85, "x"),
                new KnowledgeSnippet("s", "Y", 0.80, "y"),
                new KnowledgeSnippet("s", "Z", 0.75, "z"),
                new KnowledgeSnippet("s", "RECOVERED", 0.50, "lexical-strong-but-dense-weak")
        );
        List<KnowledgeSnippet> lexical = List.of(
                new KnowledgeSnippet("s", "RECOVERED", 1.0, "lexical-perfect-match")
        );

        List<RetrievalCandidate> merged = merger.merge(dense, lexical, 5);

        assertThat(merged.getFirst().chunkId()).isEqualTo("RECOVERED");
    }
}
