package com.calendar.users.configuration;

import com.calendar.users.exception.RequestIds;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import reactor.core.publisher.Mono;

/**
 * Carries the current request id on calls to other Wely services, read from the Reactor Context
 * {@link RequestIdWebFilter} wrote. A call made outside a request (a background loop) has none and
 * goes without the header.
 *
 * <p>For Wely services only: an external client (Keycloak) has no use for our ids and should not
 * learn them.
 */
public class RequestIdExchangeFilter implements ExchangeFilterFunction {

    @Override
    public Mono<ClientResponse> filter(ClientRequest request, ExchangeFunction next) {
        return Mono.deferContextual(context -> next.exchange(context.<String>getOrEmpty(RequestIds.REQUEST_ID_ATTRIBUTE)
                .map(requestId -> ClientRequest.from(request).header(RequestIds.REQUEST_ID_HEADER, requestId).build())
                .orElse(request)));
    }
}
