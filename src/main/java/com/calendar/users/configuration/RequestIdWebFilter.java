package com.calendar.users.configuration;

import com.calendar.users.exception.RequestIds;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

import java.util.regex.Pattern;

/**
 * Adopts the request id the gateway generated, so one id follows a request through every service.
 *
 * <p>Runs first, before security, so even a refused request answers and logs under it. The header
 * is only trusted when it looks like an id: anything else — missing, oversized, odd characters —
 * falls back to WebFlux's own id rather than putting caller-chosen text in the logs. The id is
 * stored on the exchange and in the Reactor Context (for the outgoing calls), and returned on the
 * response.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdWebFilter implements WebFilter {

    static final Pattern VALID_REQUEST_ID = Pattern.compile("^[A-Za-z0-9-]{8,64}$");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String header = exchange.getRequest().getHeaders().getFirst(RequestIds.REQUEST_ID_HEADER);
        String requestId = header != null && VALID_REQUEST_ID.matcher(header).matches()
                ? header
                : exchange.getRequest().getId();

        exchange.getAttributes().put(RequestIds.REQUEST_ID_ATTRIBUTE, requestId);
        exchange.getResponse().getHeaders().set(RequestIds.REQUEST_ID_HEADER, requestId);

        return chain.filter(exchange)
                .contextWrite(Context.of(RequestIds.REQUEST_ID_ATTRIBUTE, requestId));
    }
}
