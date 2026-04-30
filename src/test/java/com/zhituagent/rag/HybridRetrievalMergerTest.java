package com.zhituagent.rag;

import com.zhituagent.config.RagProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HybridRetrievalMergerTest {

    @Test
    void shouldMergeDenseAndLexicalCandidatesByChunkId() {
        HybridRetrievalMerger merger = new HybridRetrievalMerger();

        List<KnowledgeSnippet> dense = List.of(
                new KnowledgeSnippet("dense-source", "chunk-1", 0.92, "第一段 dense"),
                new KnowledgeSnippet("dense-source", "chunk-2", 0.80, "第二段 dense")
        );
        List<KnowledgeSnippet> lexical = List.of(
                new KnowledgeSnippet("lexical-source", "chunk-2", 1.00, "第二段 lexical"),
                new KnowledgeSnippet("lexical-source", "chunk-3", 0.75, "第三段 lexical")
        );

        List<RetrievalCandidate> merged = merger.merge(dense, lexical, 4);

        assertThat(merged).hasSize(3);
        assertThat(merged.getFirst().chunkId()).isEqualTo("chunk-2");
        assertThat(merged.getFirst().denseScore()).isEqualTo(0.80);
        assertThat(merged.getFirst().lexicalScore()).isEqualTo(1.00);
        assertThat(merged.getFirst().score()).isGreaterThan(merged.get(1).score());
    }

    @Test
    void shouldDelegateToRrfWhenStrategyConfigured() {
        RagProperties properties = new RagProperties();
        properties.setFusionStrategy("rrf");
        HybridRetrievalMerger merger = new HybridRetrievalMerger(properties, new RrfFusionMerger());

        List<KnowledgeSnippet> dense = List.of(
                new KnowledgeSnippet("s", "chunk-A", 0.9, "alpha")
        );
        List<KnowledgeSnippet> lexical = List.of(
                new KnowledgeSnippet("s", "chunk-A", 0.5, "alpha-lex")
        );

        List<RetrievalCandidate> merged = merger.merge(dense, lexical, 5);

        // RRF score for chunk-A appearing rank 0 in both = 1/60 + 1/60 ≈ 0.0333
        // The linear strategy would give 0.9*0.7 + 0.5*0.3 + 0.15 = 0.93
        assertThat(merged.getFirst().score()).isLessThan(0.1);
    }

    @Test
    void shouldFallBackToLinearWhenStrategyUnknown() {
        RagProperties properties = new RagProperties();
        properties.setFusionStrategy("garbage");
        HybridRetrievalMerger merger = new HybridRetrievalMerger(properties, new RrfFusionMerger());

        List<KnowledgeSnippet> dense = List.of(new KnowledgeSnippet("s", "X", 0.9, "x"));
        List<KnowledgeSnippet> lexical = List.of(new KnowledgeSnippet("s", "X", 0.5, "x-lex"));

        List<RetrievalCandidate> merged = merger.merge(dense, lexical, 5);

        // Linear path produces a score >> RRF's ~0.033
        assertThat(merged.getFirst().score()).isGreaterThan(0.5);
    }
}
