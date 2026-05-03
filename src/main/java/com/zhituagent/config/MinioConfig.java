package com.zhituagent.config;

import io.minio.MinioClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MinIO client wiring for v3 file ingestion pipeline.
 *
 * <p>Gated by {@code zhitu.infrastructure.minio-enabled=true} so unit tests
 * stay lightweight without a MinIO instance — the file-ingestion path is
 * a strict opt-in feature.
 *
 * <p>Local dev points at {@code http://localhost:9000} with the dev root
 * credentials in {@code docker-compose.yml}; production points at the
 * cloud MinIO behind IP-whitelist firewall, with credentials in
 * {@code .env} (never committed).
 */
@Configuration
@EnableConfigurationProperties(MinioProperties.class)
@ConditionalOnProperty(prefix = "zhitu.infrastructure", name = "minio-enabled", havingValue = "true")
public class MinioConfig {

    @Bean
    public MinioClient minioClient(MinioProperties props) {
        return MinioClient.builder()
                .endpoint(props.getEndpoint())
                .credentials(props.getAccessKey(), props.getSecretKey())
                .build();
    }
}
