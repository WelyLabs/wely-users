package com.calendar.users.infrastructure.media.adapters;

import com.calendar.users.domain.ports.PictureStorage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.time.Duration;

/**
 * Stores pictures in a private bucket through the S3 API. Failures become MEDIA_ERROR through
 * {@code InfrastructureErrorAspect}.
 *
 * <p>Every object, and every signed read, says {@code Cache-Control: private}: the hostname sits
 * behind Cloudflare, and a shared cache keeping a private picture would hand it to whoever asks
 * next with any URL that maps to the same cache entry.
 */
@Component
public class S3PictureStorageAdapter implements PictureStorage {

    static final String CACHE_CONTROL = "private, max-age=3600";

    private final S3AsyncClient client;
    private final S3Presigner presigner;
    private final String bucket;

    public S3PictureStorageAdapter(S3AsyncClient client, S3Presigner presigner,
                                   @Value("${app.media.bucket}") String bucket) {
        this.client = client;
        this.presigner = presigner;
        this.bucket = bucket;
    }

    @Override
    public Mono<Void> put(String key, byte[] content, String contentType) {
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(contentType)
                .cacheControl(CACHE_CONTROL)
                .build();
        return Mono.fromFuture(() -> client.putObject(request, AsyncRequestBody.fromBytes(content))).then();
    }

    @Override
    public Mono<Void> delete(String key) {
        return Mono.fromFuture(() -> client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build()))
                .then();
    }

    @Override
    public String signedUrl(String key, Duration ttl) {
        GetObjectRequest read = GetObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .responseCacheControl(CACHE_CONTROL)
                .build();
        return presigner.presignGetObject(GetObjectPresignRequest.builder()
                        .signatureDuration(ttl)
                        .getObjectRequest(read)
                        .build())
                .url()
                .toString();
    }
}
