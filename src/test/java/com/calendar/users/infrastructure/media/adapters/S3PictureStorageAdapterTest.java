package com.calendar.users.infrastructure.media.adapters;

import com.calendar.users.configuration.MediaClientConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.test.StepVerifier;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The presigner here is the real one: signing is local computation, so the URL can be checked
 * without any storage running. Uploads and deletions go to a mocked client.
 */
class S3PictureStorageAdapterTest {

    private S3AsyncClient client;
    private S3Presigner presigner;
    private S3PictureStorageAdapter storage;

    @BeforeEach
    void setUp() {
        MediaClientConfig config = new MediaClientConfig("GK000000000000000000000000",
                "0000000000000000000000000000000000000000000000000000000000000000", "garage");
        client = mock(S3AsyncClient.class);
        presigner = config.mediaPresigner(URI.create("https://dev-media.welylabs.app"));
        storage = new S3PictureStorageAdapter(client, presigner, "wely-avatars");
    }

    @AfterEach
    void tearDown() {
        presigner.close();
    }

    @Test
    @DisplayName("a signed URL names the public host, the bucket path and its expiry, and asks for a private cache")
    void signedUrl_shouldTargetThePublicHostWithAnExpiry() {
        URI url = URI.create(storage.signedUrl("avatars/u1/a.jpg", Duration.ofMinutes(10)));

        assertThat(url.getScheme()).isEqualTo("https");
        assertThat(url.getHost()).isEqualTo("dev-media.welylabs.app");
        assertThat(url.getPath()).isEqualTo("/wely-avatars/avatars/u1/a.jpg");
        assertThat(url.getQuery())
                .contains("X-Amz-Expires=600")
                .contains("X-Amz-Signature=")
                .contains("response-cache-control=private, max-age=3600");
    }

    @Test
    void put_shouldWriteTheObjectWithItsTypeAndAPrivateCacheControl() {
        when(client.putObject(any(PutObjectRequest.class), any(AsyncRequestBody.class)))
                .thenReturn(CompletableFuture.completedFuture(PutObjectResponse.builder().build()));

        StepVerifier.create(storage.put("avatars/u1/a.jpg", new byte[] {1}, "image/jpeg")).verifyComplete();

        ArgumentCaptor<PutObjectRequest> request = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(client).putObject(request.capture(), any(AsyncRequestBody.class));
        assertThat(request.getValue().bucket()).isEqualTo("wely-avatars");
        assertThat(request.getValue().key()).isEqualTo("avatars/u1/a.jpg");
        assertThat(request.getValue().contentType()).isEqualTo("image/jpeg");
        assertThat(request.getValue().cacheControl()).isEqualTo(S3PictureStorageAdapter.CACHE_CONTROL);
    }

    @Test
    void put_shouldFail_whenTheStorageFails() {
        when(client.putObject(any(PutObjectRequest.class), any(AsyncRequestBody.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("storage down")));

        StepVerifier.create(storage.put("avatars/u1/a.jpg", new byte[] {1}, "image/jpeg"))
                .expectError(IllegalStateException.class)
                .verify();
    }

    @Test
    void delete_shouldRemoveTheObject() {
        when(client.deleteObject(any(DeleteObjectRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(DeleteObjectResponse.builder().build()));

        StepVerifier.create(storage.delete("avatars/u1/a.jpg")).verifyComplete();

        ArgumentCaptor<DeleteObjectRequest> request = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(client).deleteObject(request.capture());
        assertThat(request.getValue().key()).isEqualTo("avatars/u1/a.jpg");
    }
}
