package com.calendar.users.exception;

import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.server.BearerTokenServerAuthenticationEntryPoint;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Answers a request without a valid token with our 401 problem, {@code AUTHENTICATION_REQUIRED}.
 *
 * <p>The {@code WWW-Authenticate: Bearer …} challenge is left to Spring's
 * {@link BearerTokenServerAuthenticationEntryPoint}, which knows how to describe an invalid or
 * expired token; it only loses the right to complete the response, so the body is ours.
 */
@Component
public class ProblemAuthenticationEntryPoint implements ServerAuthenticationEntryPoint {

    private final BearerTokenServerAuthenticationEntryPoint challenge = new BearerTokenServerAuthenticationEntryPoint();
    private final ProblemResponseWriter problems;

    public ProblemAuthenticationEntryPoint(ProblemResponseWriter problems) {
        this.problems = problems;
    }

    @Override
    public Mono<Void> commence(ServerWebExchange exchange, AuthenticationException ex) {
        return challenge.commence(HeadersOnlyExchange.of(exchange), ex)
                .then(Mono.defer(() -> problems.write(exchange,
                        new BusinessException(BusinessErrorCode.AUTHENTICATION_REQUIRED))));
    }
}
