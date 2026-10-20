package com.calendar.users.configuration;

import com.calendar.users.exception.RequestIds;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RequestIdExchangeFilterTest {

    private final RequestIdExchangeFilter filter = new RequestIdExchangeFilter();
    private final ClientRequest request = ClientRequest.create(HttpMethod.GET, URI.create("http://wely-social:8083/x")).build();

    private ClientRequest sentWith(Context context) {
        AtomicReference<ClientRequest> sent = new AtomicReference<>();
        StepVerifier.create(filter.filter(request, forwarded -> {
                            sent.set(forwarded);
                            return Mono.just(ClientResponse.create(HttpStatus.OK).build());
                        })
                        .contextWrite(context))
                .expectNextCount(1)
                .expectComplete()
                .verify(Duration.ofSeconds(10));
        return sent.get();
    }

    @Test
    void filter_shouldCopyTheRequestIdFromTheContext_whenThereIsOne() {
        ClientRequest sent = sentWith(Context.of(RequestIds.REQUEST_ID_ATTRIBUTE, "abcd1234-req"));

        assertThat(sent.headers().getFirst(RequestIds.REQUEST_ID_HEADER)).isEqualTo("abcd1234-req");
    }

    @Test
    void filter_shouldSendTheRequestUnchanged_whenNoRequestIsBeingServed() {
        ClientRequest sent = sentWith(Context.empty());

        assertThat(sent).isSameAs(request);
        assertThat(sent.headers().containsHeader(RequestIds.REQUEST_ID_HEADER)).isFalse();
    }
}
