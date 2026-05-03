package com.zhituagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "zhitu.elasticsearch")
public class EsProperties {

    private String host = "localhost";
    private int port = 9200;
    private String scheme = "http";
    private String username = "";
    private String password = "";
    private String indexName = "zhitu_agent_knowledge";
    /**
     * Dense vector dimension. Must match the embedding model output.
     * Qwen3-Embedding-8B defaults to 4096 (also the ES 8.10 dense_vector ceiling).
     * EsIndexInitializer will fail-fast at startup if the actual model dim mismatches.
     */
    private int vectorDim = 4096;

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getScheme() {
        return scheme;
    }

    public void setScheme(String scheme) {
        this.scheme = scheme;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getIndexName() {
        return indexName;
    }

    public void setIndexName(String indexName) {
        this.indexName = indexName;
    }

    public int getVectorDim() {
        return vectorDim;
    }

    public void setVectorDim(int vectorDim) {
        this.vectorDim = vectorDim;
    }

    public boolean hasAuth() {
        return username != null && !username.isBlank()
                && password != null && !password.isBlank();
    }
}
