package com.calendar.users.configuration;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MediaApiClientConfigTest {

    @Test
    void mediaApiWebClient_shouldSendTheSecretToTheBaseUrl() {
        List<ClientRequest> sent = new ArrayList<>();
        WebClient client = new MediaApiClientConfig().mediaApiWebClient("http://wely-media:8085", "s3cret").mutate()
                .exchangeFunction(request -> {
                    sent.add(request);
                    return Mono.just(ClientResponse.create(HttpStatus.NO_CONTENT).build());
                })
                .build();

        client.delete().uri("/internal/media").retrieve().toBodilessEntity().block();

        assertThat(sent.getFirst().url()).isEqualTo(URI.create("http://wely-media:8085/internal/media"));
        assertThat(sent.getFirst().headers().getFirst(MediaApiClientConfig.SECRET_HEADER)).isEqualTo("s3cret");
    }
}
