package com.zhituagent.file;

import com.zhituagent.config.MinioProperties;
import io.minio.BucketExistsArgs;
import io.minio.ComposeObjectArgs;
import io.minio.ComposeSource;
import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.http.Method;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * MinIO object-storage facade for the file ingestion pipeline.
 *
 * <p>Wraps the raw {@link MinioClient} with bucket-aware helpers that match the
 * upload pipeline's needs: single-shot put, chunk merge (composeObject),
 * presigned download URL, and cleanup. Initialised with a startup
 * bucket-ensure step so the bucket always exists by the time controllers
 * accept uploads.
 *
 * <p>Bean is conditional on a {@link MinioClient} bean being present, so when
 * {@code zhitu.infrastructure.minio-enabled=false} this service is absent and
 * other code can {@code @Autowired(required=false)} it for graceful degradation.
 */
@Service
@ConditionalOnBean(MinioClient.class)
public class MinioStorageService {

    private static final Logger log = LoggerFactory.getLogger(MinioStorageService.class);
    private static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";

    private final MinioClient client;
    private final MinioProperties props;

    public MinioStorageService(MinioClient client, MinioProperties props) {
        this.client = Objects.requireNonNull(client);
        this.props = Objects.requireNonNull(props);
    }

    /**
     * Idempotent bucket bootstrap. Run once on startup so subsequent operations
     * never race on bucket existence.
     */
    @PostConstruct
    public void ensureBucketExists() {
        try {
            boolean exists = client.bucketExists(BucketExistsArgs.builder()
                    .bucket(props.getBucket())
                    .build());
            if (!exists) {
                client.makeBucket(MakeBucketArgs.builder()
                        .bucket(props.getBucket())
                        .build());
                log.info("MinIO bucket created on startup bucket={}", props.getBucket());
            } else {
                log.info("MinIO bucket already exists bucket={}", props.getBucket());
            }
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to ensure MinIO bucket: " + props.getBucket(), e);
        }
    }

    public void putObject(String objectKey, InputStream stream, long size, String contentType) {
        String resolvedContentType = contentType != null ? contentType : DEFAULT_CONTENT_TYPE;
        try {
            client.putObject(PutObjectArgs.builder()
                    .bucket(props.getBucket())
                    .object(objectKey)
                    .stream(stream, size, -1)
                    .contentType(resolvedContentType)
                    .build());
            log.info("MinIO put completed bucket={} object={} size={} contentType={}",
                    props.getBucket(), objectKey, size, resolvedContentType);
        } catch (Exception e) {
            throw new IllegalStateException("MinIO putObject failed: " + objectKey, e);
        }
    }

    public InputStream getObject(String objectKey) {
        try {
            return client.getObject(GetObjectArgs.builder()
                    .bucket(props.getBucket())
                    .object(objectKey)
                    .build());
        } catch (Exception e) {
            throw new IllegalStateException("MinIO getObject failed: " + objectKey, e);
        }
    }

    /**
     * Server-side merge of multiple uploaded parts into a single object. Used by
     * chunked upload to avoid re-uploading after all parts are present in MinIO.
     */
    public String composeObject(String targetObjectKey, List<String> sourceObjectKeys) {
        if (sourceObjectKeys == null || sourceObjectKeys.isEmpty()) {
            throw new IllegalArgumentException("composeObject requires at least one source key");
        }
        try {
            List<ComposeSource> sources = sourceObjectKeys.stream()
                    .map(key -> ComposeSource.builder()
                            .bucket(props.getBucket())
                            .object(key)
                            .build())
                    .collect(Collectors.toList());
            client.composeObject(ComposeObjectArgs.builder()
                    .bucket(props.getBucket())
                    .object(targetObjectKey)
                    .sources(sources)
                    .build());
            log.info("MinIO compose completed target={} parts={}",
                    targetObjectKey, sourceObjectKeys.size());
            return targetObjectKey;
        } catch (Exception e) {
            throw new IllegalStateException("MinIO composeObject failed: " + targetObjectKey, e);
        }
    }

    public void removeObject(String objectKey) {
        try {
            client.removeObject(RemoveObjectArgs.builder()
                    .bucket(props.getBucket())
                    .object(objectKey)
                    .build());
            log.info("MinIO remove completed object={}", objectKey);
        } catch (Exception e) {
            throw new IllegalStateException("MinIO removeObject failed: " + objectKey, e);
        }
    }

    /**
     * Generate a time-limited HTTP GET URL clients can use without MinIO creds.
     * Expiry is bounded by the SDK to a max of 7 days; we expose seconds in
     * config and clamp to int seconds at the API boundary.
     */
    public String presignedGetUrl(String objectKey) {
        long seconds = Math.min(props.getPresignedUrlExpirySeconds(), Integer.MAX_VALUE);
        try {
            return client.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(props.getBucket())
                    .object(objectKey)
                    .expiry((int) seconds, TimeUnit.SECONDS)
                    .build());
        } catch (Exception e) {
            throw new IllegalStateException("MinIO presigned URL failed: " + objectKey, e);
        }
    }
}
