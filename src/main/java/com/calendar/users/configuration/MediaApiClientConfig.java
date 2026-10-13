package com.calendar.users.configuration;

import io.netty.channel.ChannelOption;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

/** Client for wely-media. Spring Boot 4 no longer auto-configures a WebClient.Builder. */
@Configuration
public class MediaApiClientConfig {

    /** wely-media knows its callers by this header, not by a user token. */
    static final String SECRET_HEADER = "X-Internal-Secret";
    static final Duration TIMEOUT = Duration.ofSeconds(10);

    @Bean
    public WebClient mediaApiWebClient(@Value("${app.media-api.url}") String url,
                                       @Value("${app.media-api.secret}") String secret) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) TIMEOUT.toMillis())
                .responseTimeout(TIMEOUT);

        return WebClient.builder()
                .baseUrl(url)
                .defaultHeader(SECRET_HEADER, secret)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }
}
