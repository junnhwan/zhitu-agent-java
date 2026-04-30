package com.zhituagent.rag;

import com.zhituagent.config.RagProperties;
import com.zhituagent.config.RerankProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RagRetrieverTest {

    @Test
    void shouldPreprocessQueryAndApplyRerankOrdering() {
        AtomicReference<String> capturedQuery = new AtomicReference<>();
        KnowledgeIngestService ingestService = new KnowledgeIngestService(new DocumentSplitter()) {
            @Override
            public List<KnowledgeSnippet> search(String query, int limit) {
                capturedQuery.set(query);
                return List.of(
                        new KnowledgeSnippet("dense-first", "chunk-1", 0.91, "这段内容和问题关系一般"),
                        new KnowledgeSnippet("phase-two-plan", "chunk-2", 0.83, "第一阶段先做好最简单的 Context 管理策略、记忆机制、RAG 检索、会话管理、SSE 对话问答、ToolUse。")
                );
            }
        };

        RerankClient rerankClient = (query, candidates, topN) -> new RerankClient.RerankResponse(
                "Qwen/Qwen3-Reranker-8B",
                List.of(
                        new RerankClient.RerankResult(1, 0.99),
                        new RerankClient.RerankResult(0, 0.12)
                )
        );

        RagRetriever retriever = new RagRetriever(
                ingestService,
                new QueryPreprocessor(),
                new LexicalRetriever(ingestService),
                new HybridRetrievalMerger(),
                rerankClient,
                ragProperties(false, 10),
                rerankProperties(true, 20, 5, "https://router.tumuer.me/v1/rerank", "demo-key", "Qwen/Qwen3-Reranker-8B")
        );

        RagRetrievalResult result = retriever.retrieveDetailed("  第一阶段先做什么？？  ", 2);

        assertThat(capturedQuery.get()).isEqualTo("第一阶段先做什么");
        assertThat(result.retrievalMode()).isEqualTo("dense-rerank");
        assertThat(result.retrievalCandidateCount()).isEqualTo(2);
        assertThat(result.rerankModel()).isEqualTo("Qwen/Qwen3-Reranker-8B");
        assertThat(result.rerankTopScore()).isEqualTo(0.99);
        assertThat(result.snippets()).hasSize(2);
        assertThat(result.snippets().getFirst().source()).isEqualTo("phase-two-plan");
        assertThat(result.snippets().getFirst().denseScore()).isEqualTo(0.83);
        assertThat(result.snippets().getFirst().rerankScore()).isEqualTo(0.99);
        assertThat(result.snippets().getFirst().score()).isEqualTo(0.99);
    }

    @Test
    void shouldFallbackToDenseRankingWhenRerankFails() {
        KnowledgeIngestService ingestService = new KnowledgeIngestService(new DocumentSplitter()) {
            @Override
            public List<KnowledgeSnippet> search(String query, int limit) {
                return List.of(
                        new KnowledgeSnippet("dense-first", "chunk-1", 0.91, "第一段"),
                        new KnowledgeSnippet("dense-second", "chunk-2", 0.83, "第二段")
                );
            }
        };

        RerankClient rerankClient = (query, candidates, topN) -> {
            throw new IllegalStateException("rerank unavailable");
        };

        RagRetriever retriever = new RagRetriever(
                ingestService,
                new QueryPreprocessor(),
                new LexicalRetriever(ingestService),
                new HybridRetrievalMerger(),
                rerankClient,
                ragProperties(false, 10),
                rerankProperties(true, 20, 5, "https://router.tumuer.me/v1/rerank", "demo-key", "Qwen/Qwen3-Reranker-8B")
        );

        RagRetrievalResult result = retriever.retrieveDetailed("第一阶段先做什么？", 2);

        assertThat(result.retrievalMode()).isEqualTo("dense");
        assertThat(result.retrievalCandidateCount()).isEqualTo(2);
        assertThat(result.rerankModel()).isBlank();
        assertThat(result.rerankTopScore()).isEqualTo(0.0);
        assertThat(result.snippets()).hasSize(2);
        assertThat(result.snippets().getFirst().source()).isEqualTo("dense-first");
        assertThat(result.snippets().getFirst().score()).isEqualTo(0.91);
    }

    @Test
    void shouldUseHybridRecallAndApplyRerankToMergedCandidates() {
        KnowledgeIngestService ingestService = new KnowledgeIngestService(new DocumentSplitter()) {
            @Override
            public List<KnowledgeSnippet> search(String query, int limit) {
                return List.of(
                        new KnowledgeSnippet("dense-source", "chunk-1", 0.61, "泛化的 dense 结果"),
                        new KnowledgeSnippet("dense-source", "chunk-2", 0.54, "另一个 dense 结果")
                );
            }

            @Override
            public List<KnowledgeSnippet> lexicalSearch(String query, int limit) {
                return List.of(
                        new KnowledgeSnippet("lexical-source", "chunk-3", 0.97, "第一阶段先做好最简单的 Context 管理策略、记忆机制、RAG 检索、会话管理、SSE 对话问答、ToolUse。")
                );
            }
        };

        RerankClient rerankClient = (query, candidates, topN) -> new RerankClient.RerankResponse(
                "Qwen/Qwen3-Reranker-8B",
                List.of(
                        new RerankClient.RerankResult(0, 0.98),
                        new RerankClient.RerankResult(1, 0.41)
                )
        );

        RagRetriever retriever = new RagRetriever(
                ingestService,
                new QueryPreprocessor(),
                new LexicalRetriever(ingestService),
                new HybridRetrievalMerger(),
                rerankClient,
                ragProperties(true, 10),
                rerankProperties(true, 20, 5, "https://router.tumuer.me/v1/rerank", "demo-key", "Qwen/Qwen3-Reranker-8B")
        );

        RagRetrievalResult result = retriever.retrieveDetailed("第一阶段先做什么？", 2);

        assertThat(result.retrievalMode()).isEqualTo("hybrid-rerank");
        assertThat(result.retrievalCandidateCount()).isEqualTo(3);
        assertThat(result.snippets().getFirst().source()).isEqualTo("lexical-source");
        assertThat(result.snippets().getFirst().rerankScore()).isEqualTo(0.98);
    }

    @Test
    void shouldApplyRerankCalibrationWhenStructuredAnswerIsMoreSpecific() {
        KnowledgeIngestService ingestService = new KnowledgeIngestService(new DocumentSplitter()) {
            @Override
            public List<KnowledgeSnippet> search(String query, int limit) {
                return List.of(
                        new KnowledgeSnippet(
                                "phase-one-vague",
                                "chunk-1",
                                0.95,
                                "Q: 第一版六项能力先做什么？\nA: 第一版先把整体主链跑起来，先做基础能力，后面再继续细化。"
                        ),
                        new KnowledgeSnippet(
                                "phase-one-precise",
                                "chunk-2",
                                0.89,
                                "Q: 第一版优先六项能力是什么？\nA: 第一版优先 Context、Memory、RAG、Session、SSE、ToolUse 六项能力。"
                        )
                );
            }
        };

        RerankClient rerankClient = (query, candidates, topN) -> new RerankClient.RerankResponse(
                "Qwen/Qwen3-Reranker-8B",
                List.of(
                        new RerankClient.RerankResult(0, 0.999741),
                        new RerankClient.RerankResult(1, 0.998683)
                )
        );

        RagRetriever retriever = new RagRetriever(
                ingestService,
                new QueryPreprocessor(),
                new LexicalRetriever(ingestService),
                new HybridRetrievalMerger(),
                rerankClient,
                ragProperties(false, 10),
                rerankProperties(true, 20, 5, "https://router.tumuer.me/v1/rerank", "demo-key", "Qwen/Qwen3-Reranker-8B")
        );

        RagRetrievalResult result = retriever.retrieveDetailed("第一版六项能力先做什么？", 2);

        assertThat(result.retrievalMode()).isEqualTo("dense-rerank");
        assertThat(result.snippets()).hasSize(2);
        assertThat(result.snippets().getFirst().source()).isEqualTo("phase-one-precise");
        assertThat(result.snippets().getFirst().rerankScore()).isEqualTo(0.998683);
        assertThat(result.snippets().getFirst().score()).isGreaterThan(result.snippets().get(1).score());
    }

    @Test
    void shouldRejectLowConfidenceRerankResult() {
        KnowledgeIngestService ingestService = new KnowledgeIngestService(new DocumentSplitter()) {
            @Override
            public List<KnowledgeSnippet> search(String query, int limit) {
                return List.of(
                        new KnowledgeSnippet("openclaw-deploy", "chunk-1", 0.72, "Q: 我在什么时候在服务器上部署好了openclaw并完成测试 A: 4月28号晚上九点"),
                        new KnowledgeSnippet("phase-one-plan", "chunk-2", 0.68, "Q: 第一版先做什么？A: 第一阶段先做好最简单的 Context 管理策略、记忆机制、RAG 检索、会话管理、SSE 对话问答、ToolUse。")
                );
            }
        };

        RerankClient rerankClient = (query, candidates, topN) -> new RerankClient.RerankResponse(
                "Qwen/Qwen3-Reranker-8B",
                List.of(
                        new RerankClient.RerankResult(0, 0.02),
                        new RerankClient.RerankResult(1, 0.01)
                )
        );

        RagRetriever retriever = new RagRetriever(
                ingestService,
                new QueryPreprocessor(),
                new LexicalRetriever(ingestService),
                new HybridRetrievalMerger(),
                rerankClient,
                ragProperties(true, 10, 0.15),
                rerankProperties(true, 20, 5, "https://router.tumuer.me/v1/rerank", "demo-key", "Qwen/Qwen3-Reranker-8B")
        );

        RagRetrievalResult result = retriever.retrieveDetailed("今天星期几", 1);

        assertThat(result.snippets()).isEmpty();
        assertThat(result.retrievalMode()).isEqualTo("hybrid-rerank");
        assertThat(result.retrievalCandidateCount()).isEqualTo(2);
        assertThat(result.rerankModel()).isEqualTo("Qwen/Qwen3-Reranker-8B");
        assertThat(result.rerankTopScore()).isEqualTo(0.02);
    }

    private RerankProperties rerankProperties(boolean enabled,
                                              int recallTopK,
                                              int finalTopK,
                                              String url,
                                              String apiKey,
                                              String modelName) {
        RerankProperties properties = new RerankProperties();
        properties.setEnabled(enabled);
        properties.setRecallTopK(recallTopK);
        properties.setFinalTopK(finalTopK);
        properties.setUrl(url);
        properties.setApiKey(apiKey);
        properties.setModelName(modelName);
        return properties;
    }

    private RagProperties ragProperties(boolean hybridEnabled, int lexicalTopK) {
        return ragProperties(hybridEnabled, lexicalTopK, 0.15);
    }

    private RagProperties ragProperties(boolean hybridEnabled, int lexicalTopK, double minAcceptedScore) {
        RagProperties properties = new RagProperties();
        properties.setHybridEnabled(hybridEnabled);
        properties.setLexicalTopK(lexicalTopK);
        properties.setMinAcceptedScore(minAcceptedScore);
        return properties;
    }
}
