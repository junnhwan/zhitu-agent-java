package com.zhituagent.eval;

import java.util.List;
import java.util.Set;

/**
 * Standard IR ranking metrics evaluated at cutoff K against a set of relevant document/source IDs.
 * Binary relevance only — extend to graded if/when ground truth supports it.
 */
final class RankingMetrics {

    private RankingMetrics() {
    }

    static boolean hitAtK(List<String> retrieved, Set<String> relevant, int k) {
        if (retrieved == null || relevant == null || relevant.isEmpty()) {
            return false;
        }
        int limit = Math.min(k, retrieved.size());
        for (int i = 0; i < limit; i++) {
            if (relevant.contains(retrieved.get(i))) {
                return true;
            }
        }
        return false;
    }

    static double recallAtK(List<String> retrieved, Set<String> relevant, int k) {
        if (retrieved == null || relevant == null || relevant.isEmpty()) {
            return 0.0;
        }
        int limit = Math.min(k, retrieved.size());
        long hits = 0L;
        for (int i = 0; i < limit; i++) {
            if (relevant.contains(retrieved.get(i))) {
                hits++;
            }
        }
        return hits / (double) relevant.size();
    }

    static double mrrAtK(List<String> retrieved, Set<String> relevant, int k) {
        if (retrieved == null || relevant == null || relevant.isEmpty()) {
            return 0.0;
        }
        int limit = Math.min(k, retrieved.size());
        for (int i = 0; i < limit; i++) {
            if (relevant.contains(retrieved.get(i))) {
                return 1.0 / (i + 1);
            }
        }
        return 0.0;
    }

    static double ndcgAtK(List<String> retrieved, Set<String> relevant, int k) {
        if (retrieved == null || relevant == null || relevant.isEmpty()) {
            return 0.0;
        }
        int limit = Math.min(k, retrieved.size());
        double dcg = 0.0;
        for (int i = 0; i < limit; i++) {
            if (relevant.contains(retrieved.get(i))) {
                dcg += 1.0 / log2(i + 2.0);
            }
        }
        int idealHits = Math.min(k, relevant.size());
        double idcg = 0.0;
        for (int i = 0; i < idealHits; i++) {
            idcg += 1.0 / log2(i + 2.0);
        }
        return idcg == 0.0 ? 0.0 : dcg / idcg;
    }

    static double keywordCoverage(String text, List<String> keywords) {
        if (text == null || text.isBlank() || keywords == null || keywords.isEmpty()) {
            return 0.0;
        }
        String lower = text.toLowerCase();
        long total = 0L;
        long hits = 0L;
        for (String keyword : keywords) {
            if (keyword == null || keyword.isBlank()) {
                continue;
            }
            total++;
            if (lower.contains(keyword.toLowerCase())) {
                hits++;
            }
        }
        return total == 0L ? 0.0 : hits / (double) total;
    }

    private static double log2(double value) {
        return Math.log(value) / Math.log(2.0);
    }
}
