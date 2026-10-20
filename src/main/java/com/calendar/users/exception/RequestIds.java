package com.calendar.users.exception;

import org.springframework.web.server.ServerWebExchange;

/**
 * The request id that ties a response to its log lines, across services.
 *
 * <p>The gateway generates it and forwards it as {@value #REQUEST_ID_HEADER};
 * {@code RequestIdWebFilter} stores it on the exchange under {@value #REQUEST_ID_ATTRIBUTE} and in
 * the Reactor Context under the same key, and outgoing calls to other Wely services carry it on.
 */
public final class RequestIds {

    /** Header carrying the id between the gateway, the services and their responses. */
    public static final String REQUEST_ID_HEADER = "X-Request-Id";

    /** Exchange attribute and Reactor Context key holding the id. */
    public static final String REQUEST_ID_ATTRIBUTE = "wely.requestId";

    private RequestIds() {
    }

    /** The propagated id, or WebFlux's own when no filter stored one (a test binding a controller alone). */
    public static String of(ServerWebExchange exchange) {
        String requestId = exchange.getAttribute(REQUEST_ID_ATTRIBUTE);
        return requestId != null ? requestId : exchange.getRequest().getId();
    }
}
