package com.zhituagent.rag;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.elasticsearch._types.query_dsl.Operator;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import co.elastic.clients.elasticsearch.indices.ExistsRequest;
import com.zhituagent.config.EmbeddingProperties;
import com.zhituagent.config.EsProperties;
import com.zhituagent.llm.OpenAiCompatibleBaseUrlNormalizer;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Elasticsearch-backed KnowledgeStore for the v3 hybrid retrieval stack.
 *
 * <ul>
 *   <li>{@link #addAll}: bulk index, {@code _id = chunkId} for UPSERT idempotency,
 *       {@code refresh=WaitFor} so eval reads see writes immediately.</li>
 *   <li>{@link #search}: KNN-only (dense leg).</li>
 *   <li>{@link #lexicalSearch}: match-only against the {@code content} field
 *       analyzed by {@code ik_max_word}/{@code ik_smart}.</li>
 *   <li>{@link #hybridSearch}: native KNN + match + rescore in one round-trip,
 *       {@code queryWeight=0.2} (KNN) / {@code rescoreQueryWeight=1.0} (BM25),
 *       modeled on PaiSmart {@code HybridSearchService}.</li>
 * </ul>
 *
 * Index is created lazily on the first {@link #addAll} call using the actual
 * embedding dimension reported by the model. The {@code __VECTOR_DIM__} placeholder
 * in {@code es-mappings/knowledge_base.json} is replaced at runtime.
 */
public class ElasticsearchKnowledgeStore implements KnowledgeStore {

    private static final Logger LOG = LoggerFactory.getLogger(ElasticsearchKnowledgeStore.class);
    private static final Duration EMBEDDING_TIMEOUT = Duration.ofSeconds(60);

    private final ElasticsearchClient esClient;
    private final EsProperties esProperties;
    private final EmbeddingProperties embeddingProperties;
    private volatile EmbeddingModel embeddingModel;
    private volatile boolean indexEnsured = false;

    public ElasticsearchKnowledgeStore(
            ElasticsearchClient esClient,
            EsProperties esProperties,
            EmbeddingProperties embeddingProperties) {
        this.esClient = esClient;
        this.esProperties = esProperties;
        this.embeddingProperties = embeddingProperties;
    }

    @Override
    public void addAll(List<KnowledgeChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }

        List<TextSegment> segments = chunks.stream()
                .map(c -> TextSegment.from(c.effectiveEmbedText()))
                .toList();
        List<Embedding> embeddings = getEmbeddingModel().embedAll(segments).content();

        int dim = embeddings.getFirst().dimension();
        ensureIndex(dim);

        List<BulkOperation> ops = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            KnowledgeChunk chunk = chunks.get(i);
            Map<String, Object> doc = new HashMap<>();
            doc.put("chunkId", chunk.chunkId());
            doc.put("source", chunk.source());
            doc.put("content", chunk.content());
            doc.put("embedText", chunk.effectiveEmbedText());
            doc.put("embedding", embeddings.get(i).vector());

            ops.add(BulkOperation.of(b -> b.index(idx -> idx
                    .index(esProperties.getIndexName())
                    .id(chunk.chunkId())
                    .document(doc))));
        }

        try {
            BulkResponse resp = esClient.bulk(BulkRequest.of(b -> b
                    .operations(ops)
                    .refresh(Refresh.WaitFor)));
            if (resp.errors()) {
                String firstError = resp.items().stream()
                        .filter(it -> it.error() != null)
                        .findFirst()
                        .map(it -> it.error().reason())
                        .orElse("unknown");
                throw new IllegalStateException("ES bulk index errors: " + firstError);
            }
        } catch (IOException e) {
            throw new IllegalStateException("ES bulk index IO error", e);
        }
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public List<KnowledgeSnippet> search(String query, int limit) {
        if (query == null || query.isBlank()) {
            return List.of();
        }

        Embedding queryEmb = getEmbeddingModel().embed(query).content();
        List<Float> queryVec = toFloatList(queryEmb.vector());
        int finalLimit = Math.max(1, limit);
        int recall = Math.max(finalLimit * 10, 100);

        try {
            SearchResponse<Map> resp = esClient.search(s -> s
                    .index(esProperties.getIndexName())
                    .knn(k -> k
                            .field("embedding")
                            .queryVector(queryVec)
                            .k(finalLimit)
                            .numCandidates(recall))
                    .source(src -> src.filter(f -> f.excludes("embedding")))
                    .size(finalLimit), Map.class);

            return resp.hits().hits().stream()
                    .map(this::hitToSnippet)
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("ES KNN search failed", e);
        }
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public List<KnowledgeSnippet> lexicalSearch(String query, int limit) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        int finalLimit = Math.max(1, limit);

        try {
            SearchResponse<Map> resp = esClient.search(s -> s
                    .index(esProperties.getIndexName())
                    .query(q -> q.match(m -> m.field("content").query(query)))
                    .source(src -> src.filter(f -> f.excludes("embedding")))
                    .size(finalLimit), Map.class);

            return resp.hits().hits().stream()
                    .map(this::hitToSnippet)
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("ES lexical search failed", e);
        }
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public List<KnowledgeSnippet> hybridSearch(String query, int limit) {
        if (query == null || query.isBlank()) {
            return List.of();
        }

        Embedding queryEmb = getEmbeddingModel().embed(query).content();
        List<Float> queryVec = toFloatList(queryEmb.vector());
        int finalLimit = Math.max(1, limit);
        int recall = Math.max(finalLimit * 10, 100);

        try {
            SearchResponse<Map> resp = esClient.search(s -> s
                    .index(esProperties.getIndexName())
                    .knn(k -> k
                            .field("embedding")
                            .queryVector(queryVec)
                            .k(recall)
                            .numCandidates(recall))
                    .query(q -> q.match(m -> m.field("content").query(query)))
                    .rescore(r -> r
                            .windowSize(recall)
                            .query(rq -> rq
                                    .queryWeight(0.2d)
                                    .rescoreQueryWeight(1.0d)
                                    .query(rqq -> rqq.match(m -> m
                                            .field("content")
                                            .query(query)
                                            .operator(Operator.And)))))
                    .source(src -> src.filter(f -> f.excludes("embedding")))
                    .size(finalLimit), Map.class);

            return resp.hits().hits().stream()
                    .map(this::hitToSnippet)
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("ES hybrid search failed", e);
        }
    }

    @SuppressWarnings("rawtypes")
    private KnowledgeSnippet hitToSnippet(Hit<Map> hit) {
        Map source = hit.source();
        String src = source != null && source.get("source") != null
                ? source.get("source").toString() : "";
        String chunkId = source != null && source.get("chunkId") != null
                ? source.get("chunkId").toString() : hit.id();
        String content = source != null && source.get("content") != null
                ? source.get("content").toString() : "";
        double score = hit.score() != null ? hit.score() : 0.0;
        return new KnowledgeSnippet(src, chunkId, score, content);
    }

    private synchronized void ensureIndex(int dim) {
        if (indexEnsured) {
            return;
        }
        try {
            boolean exists = esClient.indices()
                    .exists(ExistsRequest.of(e -> e.index(esProperties.getIndexName())))
                    .value();
            if (!exists) {
                String mappingJson = loadMappingJson(dim);
                esClient.indices().create(CreateIndexRequest.of(c -> c
                        .index(esProperties.getIndexName())
                        .withJson(new StringReader(mappingJson))));
                LOG.info("Created ES index {} with vector_dim={}",
                        esProperties.getIndexName(), dim);
            } else {
                LOG.info("ES index {} already exists, skipping create",
                        esProperties.getIndexName());
            }
            indexEnsured = true;
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to ensure ES index " + esProperties.getIndexName(), e);
        }
    }

    private String loadMappingJson(int dim) {
        try (var is = getClass().getClassLoader()
                .getResourceAsStream("es-mappings/knowledge_base.json")) {
            if (is == null) {
                throw new IllegalStateException(
                        "Resource es-mappings/knowledge_base.json not found on classpath");
            }
            String json = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            return json.replace("__VECTOR_DIM__", String.valueOf(dim));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load ES mapping JSON", e);
        }
    }

    private EmbeddingModel getEmbeddingModel() {
        EmbeddingModel local = embeddingModel;
        if (local == null) {
            synchronized (this) {
                local = embeddingModel;
                if (local == null) {
                    validateEmbeddingConfig();
                    var builder = OpenAiEmbeddingModel.builder()
                            .baseUrl(OpenAiCompatibleBaseUrlNormalizer.normalize(
                                    embeddingProperties.getBaseUrl()))
                            .apiKey(embeddingProperties.getApiKey())
                            .modelName(embeddingProperties.getModelName())
                            .timeout(EMBEDDING_TIMEOUT);
                    if (embeddingProperties.getDimensions() > 0) {
                        builder.dimensions(embeddingProperties.getDimensions());
                    }
                    local = builder.build();
                    embeddingModel = local;
                }
            }
        }
        return local;
    }

    private void validateEmbeddingConfig() {
        require(embeddingProperties.getBaseUrl(), "zhitu.embedding.base-url");
        require(embeddingProperties.getApiKey(), "zhitu.embedding.api-key");
        require(embeddingProperties.getModelName(), "zhitu.embedding.model-name");
    }

    private void require(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    fieldName + " must not be blank when ES knowledge store is enabled");
        }
    }

    private static List<Float> toFloatList(float[] arr) {
        List<Float> list = new ArrayList<>(arr.length);
        for (float f : arr) {
            list.add(f);
        }
        return list;
    }

    @Override
    public boolean supportsNativeHybrid() {
        return true;
    }
}
