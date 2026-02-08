package com.calendar.users.infrastructure.adapters;

import com.calendar.users.configuration.properties.AwsS3Properties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.http.SdkHttpResponse;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FileAdapterTest {

    @Mock
    private S3AsyncClient s3Client;

    @Mock
    private AwsS3Properties s3Properties;

    @InjectMocks
    private FileAdapter fileAdapter;

    @Test
    void storeObject_ShouldReturnPublicUrl_WhenSuccess() {
        // Given
        String key = "users/123/profile.jpg";
        FilePart filePart = mock(FilePart.class);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.IMAGE_JPEG);

        DataBuffer dataBuffer = new DefaultDataBufferFactory().wrap("test-content".getBytes(StandardCharsets.UTF_8));

        when(s3Properties.bucketName()).thenReturn("test-bucket");
        when(s3Properties.region()).thenReturn("us-east-1");
        when(filePart.headers()).thenReturn(headers);
        when(filePart.content()).thenReturn(Flux.just(dataBuffer));

        SdkHttpResponse httpResponse = mock(SdkHttpResponse.class);
        when(httpResponse.isSuccessful()).thenReturn(true);

        PutObjectResponse putObjectResponse = (PutObjectResponse) PutObjectResponse.builder()
                .sdkHttpResponse(httpResponse)
                .build();

        when(s3Client.putObject(any(PutObjectRequest.class), any(AsyncRequestBody.class)))
                .thenReturn(CompletableFuture.completedFuture(putObjectResponse));

        // When
        Mono<String> result = fileAdapter.storeObject(filePart, key);

        // Then
        StepVerifier.create(result)
                .expectNext("https://test-bucket.s3.us-east-1.amazonaws.com/" + key)
                .verifyComplete();
    }

    @Test
    void storeObject_ShouldError_WhenS3Fails() {
        // Given
        String key = "users/123/profile.jpg";
        FilePart filePart = mock(FilePart.class);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.IMAGE_JPEG);

        DataBuffer dataBuffer = new DefaultDataBufferFactory().wrap("test-content".getBytes(StandardCharsets.UTF_8));

        when(s3Properties.bucketName()).thenReturn("test-bucket");
        when(filePart.headers()).thenReturn(headers);
        when(filePart.content()).thenReturn(Flux.just(dataBuffer));

        SdkHttpResponse httpResponse = mock(SdkHttpResponse.class);
        when(httpResponse.isSuccessful()).thenReturn(false);
        when(httpResponse.toString()).thenReturn("S3 Error");

        PutObjectResponse putObjectResponse = (PutObjectResponse) PutObjectResponse.builder()
                .sdkHttpResponse(httpResponse)
                .build();

        when(s3Client.putObject(any(PutObjectRequest.class), any(AsyncRequestBody.class)))
                .thenReturn(CompletableFuture.completedFuture(putObjectResponse));

        // When
        Mono<String> result = fileAdapter.storeObject(filePart, key);

        // Then
        StepVerifier.create(result)
                .expectError(RuntimeException.class)
                .verify();
    }
}
