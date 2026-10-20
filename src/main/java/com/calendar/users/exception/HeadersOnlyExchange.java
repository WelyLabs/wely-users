package com.calendar.users.exception;

import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * The exchange as seen by Spring's Bearer handlers: they may set the status and the
 * {@code WWW-Authenticate} header, but completing the response is a no-op, so the body can
 * still be written afterwards.
 */
final class HeadersOnlyExchange {

    private HeadersOnlyExchange() {
    }

    static ServerWebExchange of(ServerWebExchange exchange) {
        return exchange.mutate()
                .response(new ServerHttpResponseDecorator(exchange.getResponse()) {
                    @Override
                    public Mono<Void> setComplete() {
                        return Mono.empty();
                    }
                })
                .build();
    }
}
