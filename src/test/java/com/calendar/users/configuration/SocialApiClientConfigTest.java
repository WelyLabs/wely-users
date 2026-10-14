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

class SocialApiClientConfigTest {

    @Test
    void socialApiWebClient_shouldCallTheBaseUrl_withoutAnyDefaultCredential() {
        List<ClientRequest> sent = new ArrayList<>();
        WebClient client = new SocialApiClientConfig().socialApiWebClient("http://wely-social:8083").mutate()
                .exchangeFunction(request -> {
                    sent.add(request);
                    return Mono.just(ClientResponse.create(HttpStatus.OK).build());
                })
                .build();

        client.get().uri("/social-service/relationships/x").retrieve().toBodilessEntity().block();

        assertThat(sent.getFirst().url()).isEqualTo(URI.create("http://wely-social:8083/social-service/relationships/x"));
        assertThat(sent.getFirst().headers().getFirst("Authorization")).isNull();
    }
}
