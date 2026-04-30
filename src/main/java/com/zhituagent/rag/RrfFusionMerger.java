package com.zhituagent.rag;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reciprocal Rank Fusion (Cormack et al., 2009) for combining dense and lexical
 * retrieval lists. RRF only consumes ranks, not raw scores, so it sidesteps the
 * "cosine in [0..1] vs ILIKE-count" calibration that the linear weighted merger
 * needs. {@code score = Σ 1/(k + rank_i)} with the standard {@code k = 60}.
 *
 * <p>Each candidate's reported {@code score} is the RRF sum (used for sorting),
 * and {@code denseScore}/{@code lexicalScore} carry the original component scores
 * so downstream rerankers and the trace UI keep their per-channel signal.
 */
@Component
public class RrfFusionMerger {

    /** Standard RRF k. Larger k → flatter ranking influence; 60 is the canonical value. */
    private static final double K = 60.0;

    public List<RetrievalCandidate> merge(List<KnowledgeSnippet> denseSnippets,
                                          List<KnowledgeSnippet> lexicalSnippets,
                                          int limit) {
        Map<String, RetrievalCandidate> byChunkId = new LinkedHashMap<>();
        Map<String, Double> rrfScores = new LinkedHashMap<>();
        Map<String, Double> denseRaw = new LinkedHashMap<>();
        Map<String, Double> lexicalRaw = new LinkedHashMap<>();

        if (denseSnippets != null) {
            for (int rank = 0; rank < denseSnippets.size(); rank++) {
                KnowledgeSnippet snippet = denseSnippets.get(rank);
                String key = snippet.chunkId();
                byChunkId.putIfAbsent(key, asCandidate(snippet));
                rrfScores.merge(key, contribution(rank), Double::sum);
                denseRaw.put(key, Math.max(denseRaw.getOrDefault(key, 0.0), snippet.score()));
            }
        }

        if (lexicalSnippets != null) {
            for (int rank = 0; rank < lexicalSnippets.size(); rank++) {
                KnowledgeSnippet snippet = lexicalSnippets.get(rank);
                String key = snippet.chunkId();
                byChunkId.putIfAbsent(key, asCandidate(snippet));
                rrfScores.merge(key, contribution(rank), Double::sum);
                lexicalRaw.put(key, Math.max(lexicalRaw.getOrDefault(key, 0.0), snippet.score()));
            }
        }

        return byChunkId.entrySet().stream()
                .map(entry -> {
                    RetrievalCandidate base = entry.getValue();
                    String key = entry.getKey();
                    return new RetrievalCandidate(
                            base.source(),
                            base.chunkId(),
                            rrfScores.getOrDefault(key, 0.0),
                            base.content(),
                            denseRaw.getOrDefault(key, 0.0),
                            lexicalRaw.getOrDefault(key, 0.0)
                    );
                })
                .sorted((a, b) -> Double.compare(b.score(), a.score()))
                .limit(Math.max(1, limit))
                .toList();
    }

    private static double contribution(int rank) {
        return 1.0 / (K + rank);
    }

    private static RetrievalCandidate asCandidate(KnowledgeSnippet snippet) {
        return new RetrievalCandidate(
                snippet.source(),
                snippet.chunkId(),
                0.0,
                snippet.content(),
                0.0,
                0.0
        );
    }
}
