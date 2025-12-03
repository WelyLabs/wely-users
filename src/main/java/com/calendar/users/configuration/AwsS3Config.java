package com.calendar.users.configuration;

import com.calendar.users.configuration.properties.AwsS3Properties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;

@Configuration
@EnableConfigurationProperties(AwsS3Properties.class)
public class AwsS3Config {

    private final AwsS3Properties s3Properties;

    public AwsS3Config(AwsS3Properties s3Properties) {
        this.s3Properties = s3Properties;
    }

    @Bean
    public S3AsyncClient s3AsyncClient() {
        AwsBasicCredentials awsBasicCredentials = AwsBasicCredentials.create(s3Properties.accessKeyId(), s3Properties.secretAccessKey());
        return S3AsyncClient.builder()
                .region(Region.of(s3Properties.region()))
                .credentialsProvider(StaticCredentialsProvider.create(awsBasicCredentials))
                .build();
    }
}
