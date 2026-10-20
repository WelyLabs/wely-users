package com.calendar.users.configuration;

import com.calendar.users.exception.RequestIds;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

import java.net.URI;
import java.time.Duration;
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

    @Test
    void mediaApiWebClient_shouldCarryTheRequestId_onlyWhileServingARequest() {
        List<ClientRequest> sent = new ArrayList<>();
        WebClient client = new MediaApiClientConfig().mediaApiWebClient("http://wely-media:8085", "s3cret").mutate()
                .exchangeFunction(request -> {
                    sent.add(request);
                    return Mono.just(ClientResponse.create(HttpStatus.NO_CONTENT).build());
                })
                .build();

        client.delete().uri("/internal/media").retrieve().toBodilessEntity()
                .contextWrite(Context.of(RequestIds.REQUEST_ID_ATTRIBUTE, "abcd1234-req"))
                .block(Duration.ofSeconds(10));
        // The account-deletion worker calls wely-media outside any request.
        client.delete().uri("/internal/media").retrieve().toBodilessEntity().block(Duration.ofSeconds(10));

        assertThat(sent.get(0).headers().getFirst(RequestIds.REQUEST_ID_HEADER)).isEqualTo("abcd1234-req");
        assertThat(sent.get(1).headers().containsHeader(RequestIds.REQUEST_ID_HEADER)).isFalse();
    }
}
