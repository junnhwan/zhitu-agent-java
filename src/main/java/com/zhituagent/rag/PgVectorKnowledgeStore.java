package com.zhituagent.rag;

import com.zhituagent.config.EmbeddingProperties;
import com.zhituagent.config.PgVectorProperties;
import com.zhituagent.llm.OpenAiCompatibleBaseUrlNormalizer;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class PgVectorKnowledgeStore implements KnowledgeStore {

    private static final Duration EMBEDDING_TIMEOUT = Duration.ofSeconds(60);

    private final PgVectorProperties pgVectorProperties;
    private final EmbeddingProperties embeddingProperties;
    private volatile EmbeddingModel embeddingModel;
    private volatile EmbeddingStore<TextSegment> embeddingStore;

    public PgVectorKnowledgeStore(PgVectorProperties pgVectorProperties, EmbeddingProperties embeddingProperties) {
        this.pgVectorProperties = pgVectorProperties;
        this.embeddingProperties = embeddingProperties;
    }

    @Override
    public void addAll(List<KnowledgeChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }

        List<TextSegment> segments = chunks.stream()
                .map(chunk -> TextSegment.from(
                        chunk.effectiveEmbedText(),
                        Metadata.from("source", chunk.source())
                                .put("chunkId", chunk.chunkId())
                                .put("rawText", chunk.content())
                ))
                .toList();

        List<Embedding> embeddings = getEmbeddingModel().embedAll(segments).content();
        EmbeddingStore<TextSegment> store = getOrCreateStore(embeddings.getFirst().dimension());
        List<String> ids = chunks.stream()
                .map(KnowledgeChunk::chunkId)
                .map(KnowledgeStoreIds::toEmbeddingId)
                .toList();
        store.addAll(ids, embeddings, segments);
    }

    @Override
    public List<KnowledgeSnippet> search(String query, int limit) {
        if (query == null || query.isBlank()) {
            return List.of();
        }

        Embedding queryEmbedding = getEmbeddingModel().embed(query).content();
        EmbeddingStore<TextSegment> store = getOrCreateStore(queryEmbedding.dimension());
        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(Math.max(1, limit))
                .minScore(pgVectorProperties.getMinScore())
                .build();

        return store.search(request).matches().stream()
                .map(this::toSnippet)
                .toList();
    }

    @Override
    public List<KnowledgeSnippet> lexicalSearch(String query, int limit) {
        List<String> tokens = LexicalScoringUtils.tokens(query);
        if (tokens.isEmpty()) {
            return List.of();
        }

        String scoreClause = tokens.stream()
                .map(token -> "CASE WHEN text ILIKE ? THEN 1 ELSE 0 END")
                .collect(Collectors.joining(" + "));
        String whereClause = IntStream.range(0, tokens.size())
                .mapToObj(index -> "text ILIKE ?")
                .collect(Collectors.joining(" OR "));
        String sql = "SELECT embedding_id::text AS embedding_id, text, " +
                "COALESCE(metadata->>'source', '') AS source, " +
                "COALESCE(metadata->>'chunkId', '') AS chunk_id, " +
                "(" + scoreClause + ") AS lexical_score " +
                "FROM " + qualifiedTableName() + " " +
                "WHERE text IS NOT NULL AND (" + whereClause + ") " +
                "ORDER BY lexical_score DESC, embedding_id ASC " +
                "LIMIT ?";

        try (Connection connection = createDataSource().getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            int parameterIndex = 1;
            for (String token : tokens) {
                statement.setString(parameterIndex++, "%" + token + "%");
            }
            for (String token : tokens) {
                statement.setString(parameterIndex++, "%" + token + "%");
            }
            statement.setInt(parameterIndex, Math.max(1, limit));

            try (ResultSet resultSet = statement.executeQuery()) {
                List<KnowledgeSnippet> snippets = new ArrayList<>();
                while (resultSet.next()) {
                    snippets.add(new KnowledgeSnippet(
                            resultSet.getString("source"),
                            resultSet.getString("chunk_id"),
                            resultSet.getDouble("lexical_score"),
                            resultSet.getString("text"),
                            0.0,
                            0.0
                    ));
                }
                return snippets;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("failed to execute lexical pgvector search", exception);
        }
    }

    private KnowledgeSnippet toSnippet(EmbeddingMatch<TextSegment> match) {
        TextSegment embedded = match.embedded();
        String source = embedded.metadata().getString("source");
        String chunkId = embedded.metadata().getString("chunkId");
        String rawText = embedded.metadata().getString("rawText");
        String content = rawText != null && !rawText.isBlank() ? rawText : embedded.text();
        return new KnowledgeSnippet(source, chunkId, match.score(), content);
    }

    private EmbeddingModel getEmbeddingModel() {
        EmbeddingModel local = embeddingModel;
        if (local == null) {
            synchronized (this) {
                local = embeddingModel;
                if (local == null) {
                    validateEmbeddingConfig();
                    local = OpenAiEmbeddingModel.builder()
                            .baseUrl(OpenAiCompatibleBaseUrlNormalizer.normalize(embeddingProperties.getBaseUrl()))
                            .apiKey(embeddingProperties.getApiKey())
                            .modelName(embeddingProperties.getModelName())
                            .timeout(EMBEDDING_TIMEOUT)
                            .build();
                    embeddingModel = local;
                }
            }
        }
        return local;
    }

    private EmbeddingStore<TextSegment> getOrCreateStore(int dimension) {
        EmbeddingStore<TextSegment> local = embeddingStore;
        if (local == null) {
            synchronized (this) {
                local = embeddingStore;
                if (local == null) {
                    validatePgVectorConfig();
                    local = PgVectorEmbeddingStore.datasourceBuilder()
                            .datasource(createDataSource())
                            .table(qualifiedTableName())
                            .dimension(dimension)
                            .createTable(true)
                            .useIndex(false)
                            .build();
                    embeddingStore = local;
                }
            }
        }
        return local;
    }

    private DataSource createDataSource() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setServerNames(new String[]{pgVectorProperties.getHost()});
        dataSource.setPortNumbers(new int[]{pgVectorProperties.getPort()});
        dataSource.setDatabaseName(pgVectorProperties.getDatabase());
        dataSource.setUser(pgVectorProperties.getUsername());
        dataSource.setPassword(pgVectorProperties.getPassword());
        return dataSource;
    }

    private String qualifiedTableName() {
        if (pgVectorProperties.getSchema() == null || pgVectorProperties.getSchema().isBlank()) {
            return pgVectorProperties.getTable();
        }
        return pgVectorProperties.getSchema() + "." + pgVectorProperties.getTable();
    }

    private void validateEmbeddingConfig() {
        require(embeddingProperties.getBaseUrl(), "zhitu.embedding.base-url");
        require(embeddingProperties.getApiKey(), "zhitu.embedding.api-key");
        require(embeddingProperties.getModelName(), "zhitu.embedding.model-name");
    }

    private void validatePgVectorConfig() {
        require(pgVectorProperties.getHost(), "zhitu.pgvector.host");
        require(pgVectorProperties.getDatabase(), "zhitu.pgvector.database");
        require(pgVectorProperties.getUsername(), "zhitu.pgvector.username");
        require(pgVectorProperties.getPassword(), "zhitu.pgvector.password");
        require(pgVectorProperties.getTable(), "zhitu.pgvector.table");
    }

    private void require(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(fieldName + " must not be blank when pgvector store is enabled");
        }
    }
}
