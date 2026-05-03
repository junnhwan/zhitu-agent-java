package com.zhituagent.file;

import com.zhituagent.config.MinioProperties;
import io.minio.BucketExistsArgs;
import io.minio.ComposeObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.http.Method;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MinioStorageServiceTest {

    private static final String BUCKET = "zhitu-agent-files";

    private MinioClient client;
    private MinioStorageService service;

    @BeforeEach
    void setUp() {
        client = mock(MinioClient.class);
        MinioProperties props = new MinioProperties();
        props.setBucket(BUCKET);
        props.setEndpoint("http://localhost:9000");
        props.setPresignedUrlExpirySeconds(3600);
        service = new MinioStorageService(client, props);
    }

    @Test
    void ensureBucketExists_creates_when_missing() throws Exception {
        when(client.bucketExists(any(BucketExistsArgs.class))).thenReturn(false);

        service.ensureBucketExists();

        ArgumentCaptor<MakeBucketArgs> captor = ArgumentCaptor.forClass(MakeBucketArgs.class);
        verify(client).makeBucket(captor.capture());
        assertThat(captor.getValue().bucket()).isEqualTo(BUCKET);
    }

    @Test
    void ensureBucketExists_skips_make_when_already_present() throws Exception {
        when(client.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);

        service.ensureBucketExists();

        verify(client, org.mockito.Mockito.never()).makeBucket(any(MakeBucketArgs.class));
    }

    @Test
    void ensureBucketExists_wraps_minio_failure_as_illegal_state() throws Exception {
        doThrow(new RuntimeException("network down"))
                .when(client).bucketExists(any(BucketExistsArgs.class));

        assertThatThrownBy(() -> service.ensureBucketExists())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to ensure MinIO bucket");
    }

    @Test
    void putObject_uses_configured_bucket_object_and_contentType() throws Exception {
        InputStream stream = new ByteArrayInputStream("hello".getBytes());

        service.putObject("docs/test.txt", stream, 5, "text/plain");

        ArgumentCaptor<PutObjectArgs> captor = ArgumentCaptor.forClass(PutObjectArgs.class);
        verify(client).putObject(captor.capture());
        PutObjectArgs args = captor.getValue();
        assertThat(args.bucket()).isEqualTo(BUCKET);
        assertThat(args.object()).isEqualTo("docs/test.txt");
        assertThat(args.contentType()).isEqualTo("text/plain");
    }

    @Test
    void putObject_defaults_contentType_when_null() throws Exception {
        service.putObject("a.bin", new ByteArrayInputStream(new byte[]{1}), 1, null);

        ArgumentCaptor<PutObjectArgs> captor = ArgumentCaptor.forClass(PutObjectArgs.class);
        verify(client).putObject(captor.capture());
        assertThat(captor.getValue().contentType()).isEqualTo("application/octet-stream");
    }

    @Test
    void putObject_wraps_minio_failure_as_illegal_state() throws Exception {
        doThrow(new RuntimeException("io error"))
                .when(client).putObject(any(PutObjectArgs.class));

        assertThatThrownBy(() -> service.putObject(
                "x.bin", new ByteArrayInputStream(new byte[0]), 0, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MinIO putObject failed: x.bin");
    }

    @Test
    void composeObject_builds_sources_with_bucket_and_returns_target_key() throws Exception {
        String result = service.composeObject("merged.bin", List.of("p1", "p2", "p3"));

        assertThat(result).isEqualTo("merged.bin");
        ArgumentCaptor<ComposeObjectArgs> captor = ArgumentCaptor.forClass(ComposeObjectArgs.class);
        verify(client).composeObject(captor.capture());
        ComposeObjectArgs args = captor.getValue();
        assertThat(args.bucket()).isEqualTo(BUCKET);
        assertThat(args.object()).isEqualTo("merged.bin");
        assertThat(args.sources()).hasSize(3);
    }

    @Test
    void composeObject_rejects_empty_source_list() {
        assertThatThrownBy(() -> service.composeObject("merged.bin", List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one source");
    }

    @Test
    void presignedGetUrl_uses_GET_method_and_returns_url() throws Exception {
        when(client.getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class)))
                .thenReturn("http://signed.example/doc.pdf");

        String url = service.presignedGetUrl("doc.pdf");

        assertThat(url).isEqualTo("http://signed.example/doc.pdf");
        ArgumentCaptor<GetPresignedObjectUrlArgs> captor =
                ArgumentCaptor.forClass(GetPresignedObjectUrlArgs.class);
        verify(client).getPresignedObjectUrl(captor.capture());
        GetPresignedObjectUrlArgs args = captor.getValue();
        assertThat(args.method()).isEqualTo(Method.GET);
        assertThat(args.bucket()).isEqualTo(BUCKET);
        assertThat(args.object()).isEqualTo("doc.pdf");
        assertThat(args.expiry()).isEqualTo(3600);
    }

    @Test
    void removeObject_passes_bucket_and_object() throws Exception {
        service.removeObject("dead.bin");

        ArgumentCaptor<RemoveObjectArgs> captor = ArgumentCaptor.forClass(RemoveObjectArgs.class);
        verify(client).removeObject(captor.capture());
        assertThat(captor.getValue().bucket()).isEqualTo(BUCKET);
        assertThat(captor.getValue().object()).isEqualTo("dead.bin");
    }
}
