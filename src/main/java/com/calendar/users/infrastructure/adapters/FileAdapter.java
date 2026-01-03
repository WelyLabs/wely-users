package com.calendar.users.infrastructure.adapters;

import com.calendar.users.configuration.properties.AwsS3Properties;
import com.calendar.users.domain.ports.FileStorage;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.nio.ByteBuffer;
import java.util.Objects;

// todo : transférer cette classe dans service dédié
@Component
public class FileAdapter implements FileStorage {

    private final S3AsyncClient s3Client;
    private final AwsS3Properties s3Properties;

    public FileAdapter(S3AsyncClient s3Client, AwsS3Properties s3Properties) {
        this.s3Client = s3Client;
        this.s3Properties = s3Properties;
    }

    public Mono<String> storeObject(FilePart filePart, String key) {

        // 1. Récupérer le ContentType de la requête entrante
        String contentType = Objects.requireNonNull(filePart.headers().getContentType()).toString();

        // 2. Joindre le flux du fichier en un seul DataBuffer pour déterminer la taille et l'envoyer
        return DataBufferUtils.join(filePart.content())
                .flatMap(dataBuffer -> {

                    // 3. Calculer la taille exacte du fichier (pour S3 ContentLength)
                    long contentLength = dataBuffer.readableByteCount();
                    ByteBuffer byteBuffer = dataBuffer.asByteBuffer(); // Obtient le contenu en ByteBuffer

                    // 4. Construire la requête PutObject avec les métadonnées exactes
                    PutObjectRequest putRequest = PutObjectRequest.builder()
                            .bucket(s3Properties.bucketName())
                            .key(key)
                            .contentType(contentType)
                            .contentLength(contentLength) // 🚨 Taille exacte du DataBuffer
                            .build();

                    // 5. Envoyer la requête : le corps est directement le ByteBuffer
                    return Mono.fromFuture(s3Client.putObject(putRequest, AsyncRequestBody.fromByteBuffer(byteBuffer)))
                            .doFinally(signalType -> {
                                // 🚨 CORRECTION CRITIQUE : Libérer le DataBuffer après que S3 ait terminé l'envoi
                                DataBufferUtils.release(dataBuffer);
                            })
                            .mapNotNull(response -> {
                                if (response.sdkHttpResponse().isSuccessful()) {
                                    return createPublicUrl(key);
                                } else {
                                    // todo : modifier ça en une exception custom
                                    throw new RuntimeException(response.sdkHttpResponse().toString());
                                }
                            });
                });
    }

    private String createPublicUrl(String key) {
        return String.format("https://%s.s3.%s.amazonaws.com/%s", s3Properties.bucketName(), s3Properties.region(), key);
    }
}