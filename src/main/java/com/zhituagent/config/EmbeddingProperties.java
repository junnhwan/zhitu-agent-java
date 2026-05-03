package com.zhituagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "zhitu.embedding")
public class EmbeddingProperties {

    private String baseUrl = "";
    private String apiKey = "";
    private String modelName = "";
    /**
     * Optional Matryoshka-style dimension override for the OpenAI-compatible
     * {@code dimensions} parameter. When > 0 it forces the embedding model to
     * return vectors of this size (Qwen3-Embedding supports MRL truncation to
     * 1024/2048/4096). Set to {@code 2048} for ES 8.10 since its
     * {@code dense_vector} ceiling is 2048 (raised to 4096 in 8.13+).
     */
    private int dimensions = 0;

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public int getDimensions() {
        return dimensions;
    }

    public void setDimensions(int dimensions) {
        this.dimensions = dimensions;
    }
}
