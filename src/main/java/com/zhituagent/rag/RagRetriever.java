package com.zhituagent.rag;

import com.zhituagent.config.RagProperties;
import com.zhituagent.config.RerankProperties;
import com.zhituagent.metrics.RagMetricsRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

@Component
public class RagRetriever {

    private static final Logger log = LoggerFactory.getLogger(RagRetriever.class);
    private static final String DENSE_MODE = "dense";
    private static final String DENSE_RERANK_MODE = "dense-rerank";
    private static final String HYBRID_MODE = "hybrid";
    private static final String HYBRID_RERANK_MODE = "hybrid-rerank";

    private final KnowledgeIngestService knowledgeIngestService;
    private final QueryPreprocessor queryPreprocessor;
    private final LexicalRetriever lexicalRetriever;
    private final HybridRetrievalMerger hybridRetrievalMerger;
    private final RerankClient rerankClient;
    private final RagProperties ragProperties;
    private final RerankProperties rerankProperties;
    private final RagMetricsRecorder ragMetricsRecorder;
    private final RerankResultCalibrator rerankResultCalibrator;

    @Autowired
    public RagRetriever(KnowledgeIngestService knowledgeIngestService,
                        QueryPreprocessor queryPreprocessor,
                        LexicalRetriever lexicalRetriever,
                        HybridRetrievalMerger hybridRetrievalMerger,
                        RerankClient rerankClient,
                        RagProperties ragProperties,
                        RerankProperties rerankProperties,
                        RagMetricsRecorder ragMetricsRecorder) {
        this.knowledgeIngestService = knowledgeIngestService;
        this.queryPreprocessor = queryPreprocessor;
        this.lexicalRetriever = lexicalRetriever;
        this.hybridRetrievalMerger = hybridRetrievalMerger;
        this.rerankClient = rerankClient;
        this.ragProperties = ragProperties;
        this.rerankProperties = rerankProperties;
        this.ragMetricsRecorder = ragMetricsRecorder;
        this.rerankResultCalibrator = new RerankResultCalibrator();
    }

    public RagRetriever(KnowledgeIngestService knowledgeIngestService,
                        QueryPreprocessor queryPreprocessor,
                        LexicalRetriever lexicalRetriever,
                        HybridRetrievalMerger hybridRetrievalMerger,
                        RerankClient rerankClient,
                        RagProperties ragProperties,
                        RerankProperties rerankProperties) {
        this(
                knowledgeIngestService,
                queryPreprocessor,
                lexicalRetriever,
                hybridRetrievalMerger,
                rerankClient,
                ragProperties,
                rerankProperties,
                RagMetricsRecorder.noop()
        );
    }

    public RagRetriever(KnowledgeIngestService knowledgeIngestService) {
        this(
                knowledgeIngestService,
                new QueryPreprocessor(),
                new LexicalRetriever(knowledgeIngestService),
                new HybridRetrievalMerger(),
                null,
                disabledRagProperties(),
                disabledRerankProperties()
        );
    }

    public List<KnowledgeSnippet> retrieve(String query, int limit) {
        return retrieveDetailed(query, limit).snippets();
    }

    public RagRetrievalResult retrieveDetailed(String query, int limit) {
        return retrieveDetailed(query, limit, RetrievalRequestOptions.defaults());
    }

    public RagRetrievalResult retrieveDetailed(String query, int limit, RetrievalMode retrievalMode) {
        return retrieveDetailed(query, limit, RetrievalRequestOptions.withMode(retrievalMode));
    }

    public RagRetrievalResult retrieveDetailed(String query, int limit, RetrievalRequestOptions retrievalOptions) {
        long startNanos = System.nanoTime();
        RetrievalRequestOptions safeOptions = retrievalOptions == null ? RetrievalRequestOptions.defaults() : retrievalOptions;
        RetrievalMode safeMode = safeOptions.mode();
        String processedQuery = queryPreprocessor.preprocess(query);
        int finalLimit = resolveFinalLimit(limit);
        boolean rerankEnabled = safeMode.resolveRerank(isRerankReady());
        boolean hybridEnabled = safeMode.resolveHybrid(ragProperties != null && ragProperties.isHybridEnabled());
        int recallLimit = rerankEnabled
                ? Math.max(finalLimit, Math.max(1, rerankProperties.getRecallTopK()))
                : finalLimit;

        boolean nativeHybrid = hybridEnabled && knowledgeIngestService.supportsNativeHybrid();
        List<RetrievalCandidate> candidates;
        if (nativeHybrid) {
            // ES native hybrid: KNN + match + rescore in a single round-trip.
            // HybridRetrievalMerger / RrfFusionMerger are passthrough on this path.
            List<KnowledgeSnippet> hybridSnippets = filterByAllowedSources(
                    knowledgeIngestService.hybridSearch(processedQuery, recallLimit),
                    safeOptions
            );
            candidates = hybridSnippets.stream()
                    .map(snippet -> new RetrievalCandidate(
                            snippet.source(),
                            snippet.chunkId(),
                            snippet.score(),
                            snippet.content(),
                            0.0,
                            0.0
                    ))
                    .toList();
        } else if (hybridEnabled) {
            // Legacy hybrid (in-memory / pgvector): dense + lexical + RRF/linear merge.
            List<KnowledgeSnippet> denseSnippets = filterByAllowedSources(
                    knowledgeIngestService.search(processedQuery, recallLimit),
                    safeOptions
            );
            List<KnowledgeSnippet> lexicalSnippets = filterByAllowedSources(
                    lexicalRetriever.retrieve(processedQuery, Math.max(1, ragProperties.getLexicalTopK())),
                    safeOptions
            );
            candidates = hybridRetrievalMerger.merge(denseSnippets, lexicalSnippets, recallLimit);
        } else {
            // Dense-only.
            List<KnowledgeSnippet> denseSnippets = filterByAllowedSources(
                    knowledgeIngestService.search(processedQuery, recallLimit),
                    safeOptions
            );
            candidates = denseSnippets.stream()
                    .map(snippet -> new RetrievalCandidate(
                            snippet.source(),
                            snippet.chunkId(),
                            snippet.score(),
                            snippet.content(),
                            snippet.denseScore(),
                            0.0
                    ))
                    .toList();
        }

        RagRetrievalResult result = tryRerank(processedQuery, finalLimit, candidates, hybridEnabled, rerankEnabled);
        if (shouldRejectLowConfidence(result)) {
            KnowledgeSnippet rejectedTop = result.snippets().getFirst();
            log.info(
                    "RAG 候选已拒绝 rag.search.rejected reason=low_score retrievalMode={} candidateCount={} topSource={} topScore={} minAcceptedScore={} queryPreview={}",
                    result.retrievalMode(),
                    result.retrievalCandidateCount(),
                    rejectedTop.source(),
                    String.format("%.4f", rejectedTop.score()),
                    String.format("%.4f", ragProperties.getMinAcceptedScore()),
                    preview(processedQuery)
            );
            result = new RagRetrievalResult(
                    List.of(),
                    result.retrievalMode(),
                    result.retrievalCandidateCount(),
                    result.rerankModel(),
                    result.rerankTopScore()
            );
        }
        long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;
        if (result.snippets().isEmpty()) {
            ragMetricsRecorder.recordRetrieval(result.retrievalMode(), false, latencyMs, candidates.size(), 0);
            log.info(
                    "RAG 检索完成 rag.search.completed retrievalMode={} candidateCount={} resultCount=0 limit={} latencyMs={} queryPreview={}",
                    result.retrievalMode(),
                    candidates.size(),
                    finalLimit,
                    latencyMs,
                    preview(processedQuery)
            );
            return result;
        }

        KnowledgeSnippet top = result.snippets().getFirst();
        ragMetricsRecorder.recordRetrieval(
                result.retrievalMode(),
                true,
                latencyMs,
                candidates.size(),
                result.snippets().size()
        );
        log.info(
                "RAG 检索完成 rag.search.completed retrievalMode={} candidateCount={} resultCount={} limit={} latencyMs={} rerankModel={} topSource={} topScore={} queryPreview={}",
                result.retrievalMode(),
                candidates.size(),
                result.snippets().size(),
                finalLimit,
                latencyMs,
                result.rerankModel(),
                top.source(),
                String.format("%.4f", top.score()),
                preview(processedQuery)
        );
        return result;
    }

    private boolean shouldRejectLowConfidence(RagRetrievalResult result) {
        if (result == null || result.snippets().isEmpty()) {
            return false;
        }
        if (!isCosineScaledScore(result.retrievalMode())) {
            return false;
        }
        double minAcceptedScore = ragProperties == null ? 0.0 : ragProperties.getMinAcceptedScore();
        if (minAcceptedScore <= 0.0) {
            return false;
        }
        return result.snippets().getFirst().score() < minAcceptedScore;
    }

    private static boolean isCosineScaledScore(String retrievalMode) {
        return DENSE_MODE.equals(retrievalMode)
                || DENSE_RERANK_MODE.equals(retrievalMode)
                || HYBRID_RERANK_MODE.equals(retrievalMode);
    }

    private RagRetrievalResult tryRerank(String processedQuery,
                                         int finalLimit,
                                         List<RetrievalCandidate> candidates,
                                         boolean hybridEnabled,
                                         boolean rerankEnabled) {
        String baseMode = hybridEnabled ? HYBRID_MODE : DENSE_MODE;
        String rerankMode = hybridEnabled ? HYBRID_RERANK_MODE : DENSE_RERANK_MODE;
        if (candidates.isEmpty()) {
            return new RagRetrievalResult(List.of(), baseMode, 0, "", 0.0);
        }

        List<KnowledgeSnippet> baseSnippets = candidates.stream()
                .limit(finalLimit)
                .map(this::toKnowledgeSnippet)
                .toList();
        if (!rerankEnabled || candidates.size() <= 1) {
            return new RagRetrievalResult(baseSnippets, baseMode, candidates.size(), "", 0.0);
        }

        try {
            ragMetricsRecorder.recordRerankAttempt(rerankProperties.getModelName(), true);
            RerankClient.RerankResponse rerankResponse = rerankClient.rerank(processedQuery, candidates, finalLimit);
            List<KnowledgeSnippet> rerankedSnippets = rerankResultCalibrator.calibrate(
                            processedQuery,
                            candidates,
                            rerankResponse.results()
                    ).stream()
                    .limit(finalLimit)
                    .map(result -> toRerankedSnippet(
                            candidates.get(result.index()),
                            result.calibratedScore(),
                            result.rawScore()
                    ))
                    .toList();

            if (rerankedSnippets.isEmpty()) {
                return new RagRetrievalResult(baseSnippets, baseMode, candidates.size(), "", 0.0);
            }

            double topRerankScore = rerankedSnippets.getFirst().rerankScore();
            return new RagRetrievalResult(
                    rerankedSnippets,
                    rerankMode,
                    candidates.size(),
                    rerankResponse.model(),
                    topRerankScore
            );
        } catch (Exception exception) {
            ragMetricsRecorder.recordRerankAttempt(rerankProperties.getModelName(), false);
            log.warn(
                    "RAG rerank 调用失败，降级为 dense retrieval rerankUrl={} model={} message={}",
                    rerankProperties.getUrl(),
                    rerankProperties.getModelName(),
                    exception.getMessage()
            );
            return new RagRetrievalResult(baseSnippets, baseMode, candidates.size(), "", 0.0);
        }
    }

    private KnowledgeSnippet toRerankedSnippet(RetrievalCandidate candidate,
                                               double calibratedScore,
                                               double rerankScore) {
        return new KnowledgeSnippet(
                candidate.source(),
                candidate.chunkId(),
                calibratedScore,
                candidate.content(),
                candidate.denseScore(),
                rerankScore
        );
    }

    private KnowledgeSnippet toKnowledgeSnippet(RetrievalCandidate candidate) {
        return new KnowledgeSnippet(
                candidate.source(),
                candidate.chunkId(),
                candidate.score(),
                candidate.content(),
                candidate.denseScore(),
                0.0
        );
    }

    private int resolveFinalLimit(int limit) {
        int safeLimit = Math.max(1, limit);
        if (isRerankReady() && rerankProperties.getFinalTopK() > 0) {
            return Math.min(safeLimit, rerankProperties.getFinalTopK());
        }
        return safeLimit;
    }

    private List<KnowledgeSnippet> filterByAllowedSources(List<KnowledgeSnippet> snippets, RetrievalRequestOptions options) {
        if (snippets == null || snippets.isEmpty()) {
            return List.of();
        }
        if (options == null || !options.hasSourceAllowlist()) {
            return List.copyOf(snippets);
        }
        Set<String> allowedSources = options.sourceAllowlist();
        if (allowedSources.isEmpty()) {
            return List.of();
        }
        return snippets.stream()
                .filter(snippet -> allowedSources.contains(snippet.source()))
                .toList();
    }

    private boolean isRerankReady() {
        return rerankClient != null && rerankProperties != null && rerankProperties.isReady();
    }

    private static RagProperties disabledRagProperties() {
        RagProperties properties = new RagProperties();
        properties.setHybridEnabled(false);
        return properties;
    }

    private static RerankProperties disabledRerankProperties() {
        RerankProperties properties = new RerankProperties();
        properties.setEnabled(false);
        return properties;
    }

    private String preview(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return text.length() <= 48 ? text : text.substring(0, 48) + "...";
    }
}
