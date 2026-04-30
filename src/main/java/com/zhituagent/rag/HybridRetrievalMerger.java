package com.zhituagent.rag;

import com.zhituagent.config.RagProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class HybridRetrievalMerger {

    private static final String STRATEGY_RRF = "rrf";

    private final RagProperties ragProperties;
    private final RrfFusionMerger rrfFusionMerger;

    @Autowired
    public HybridRetrievalMerger(RagProperties ragProperties, RrfFusionMerger rrfFusionMerger) {
        this.ragProperties = ragProperties;
        this.rrfFusionMerger = rrfFusionMerger;
    }

    public HybridRetrievalMerger() {
        this(new RagProperties(), new RrfFusionMerger());
    }

    public List<RetrievalCandidate> merge(List<KnowledgeSnippet> denseSnippets,
                                          List<KnowledgeSnippet> lexicalSnippets,
                                          int limit) {
        if (isRrfStrategy()) {
            return rrfFusionMerger.merge(denseSnippets, lexicalSnippets, limit);
        }
        return mergeLinear(denseSnippets, lexicalSnippets, limit);
    }

    private boolean isRrfStrategy() {
        if (ragProperties == null) {
            return false;
        }
        String strategy = ragProperties.getFusionStrategy();
        if (strategy == null) {
            return false;
        }
        return STRATEGY_RRF.equals(strategy.toLowerCase(Locale.ROOT));
    }

    private List<RetrievalCandidate> mergeLinear(List<KnowledgeSnippet> denseSnippets,
                                                 List<KnowledgeSnippet> lexicalSnippets,
                                                 int limit) {
        Map<String, RetrievalCandidate> candidatesByChunkId = new LinkedHashMap<>();

        if (denseSnippets != null) {
            for (KnowledgeSnippet snippet : denseSnippets) {
                candidatesByChunkId.put(snippet.chunkId(), new RetrievalCandidate(
                        snippet.source(),
                        snippet.chunkId(),
                        snippet.score(),
                        snippet.content(),
                        snippet.denseScore(),
                        0.0
                ));
            }
        }

        if (lexicalSnippets != null) {
            for (KnowledgeSnippet snippet : lexicalSnippets) {
                candidatesByChunkId.merge(snippet.chunkId(), new RetrievalCandidate(
                        snippet.source(),
                        snippet.chunkId(),
                        snippet.score(),
                        snippet.content(),
                        0.0,
                        snippet.score()
                ), this::mergeCandidate);
            }
        }

        return candidatesByChunkId.values().stream()
                .sorted(Comparator.comparingDouble(RetrievalCandidate::score).reversed())
                .limit(Math.max(1, limit))
                .toList();
    }

    private RetrievalCandidate mergeCandidate(RetrievalCandidate existing, RetrievalCandidate incoming) {
        double denseScore = Math.max(existing.denseScore(), incoming.denseScore());
        double lexicalScore = Math.max(existing.lexicalScore(), incoming.lexicalScore());
        double combinedScore = denseScore * 0.7 + lexicalScore * 0.3 + (denseScore > 0 && lexicalScore > 0 ? 0.15 : 0.0);

        return new RetrievalCandidate(
                existing.source(),
                existing.chunkId(),
                combinedScore,
                existing.content(),
                denseScore,
                lexicalScore
        );
    }
}
