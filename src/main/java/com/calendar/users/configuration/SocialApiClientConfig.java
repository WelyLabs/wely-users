package com.calendar.users.configuration;

import io.netty.channel.ChannelOption;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

/** Client for wely-social. Calls carry the caller's own token, set per request by the adapter. */
@Configuration
public class SocialApiClientConfig {

    static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Bean
    public WebClient socialApiWebClient(@Value("${app.social-api.url}") String url) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) TIMEOUT.toMillis())
                .responseTimeout(TIMEOUT);

        return WebClient.builder()
                .baseUrl(url)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }
}
