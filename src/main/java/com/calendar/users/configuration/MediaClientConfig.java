package com.calendar.users.configuration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

/**
 * Clients for the object storage, Garage in the cluster, through the S3 API: moving to S3 would
 * change these properties, not the code.
 *
 * <p>Two endpoints, on purpose. Uploads go to the internal Service. Signed URLs are computed for
 * the public hostname, because a signature covers the host: one signed for the internal name would
 * be refused when the browser presents it through the tunnel. Path-style addressing, since there is
 * no wildcard hostname per bucket.
 */
@Configuration
public class MediaClientConfig {

    private final StaticCredentialsProvider credentials;
    private final Region region;

    public MediaClientConfig(@Value("${app.media.access-key}") String accessKey,
                             @Value("${app.media.secret-key}") String secretKey,
                             @Value("${app.media.region}") String region) {
        this.credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey));
        this.region = Region.of(region);
    }

    @Bean(destroyMethod = "close")
    public S3AsyncClient mediaClient(@Value("${app.media.internal-endpoint}") URI internalEndpoint) {
        return S3AsyncClient.builder()
                .endpointOverride(internalEndpoint)
                .region(region)
                .credentialsProvider(credentials)
                .forcePathStyle(true)
                .build();
    }

    @Bean(destroyMethod = "close")
    public S3Presigner mediaPresigner(@Value("${app.media.public-endpoint}") URI publicEndpoint) {
        return S3Presigner.builder()
                .endpointOverride(publicEndpoint)
                .region(region)
                .credentialsProvider(credentials)
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
    }
}
