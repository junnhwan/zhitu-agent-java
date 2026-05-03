package com.zhituagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * MinIO object-storage configuration for the v3 file ingestion pipeline.
 * Bucket holds raw uploads and chunked-upload merge results; presigned URLs
 * support 1h download windows for re-parsing or re-embedding.
 */
@ConfigurationProperties(prefix = "zhitu.minio")
public class MinioProperties {

    private String endpoint = "http://localhost:9000";
    private String accessKey = "";
    private String secretKey = "";
    private String bucket = "zhitu-agent-files";
    private long presignedUrlExpirySeconds = 3600;

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getAccessKey() {
        return accessKey;
    }

    public void setAccessKey(String accessKey) {
        this.accessKey = accessKey;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }

    public String getBucket() {
        return bucket;
    }

    public void setBucket(String bucket) {
        this.bucket = bucket;
    }

    public long getPresignedUrlExpirySeconds() {
        return presignedUrlExpirySeconds;
    }

    public void setPresignedUrlExpirySeconds(long presignedUrlExpirySeconds) {
        this.presignedUrlExpirySeconds = presignedUrlExpirySeconds;
    }
}
