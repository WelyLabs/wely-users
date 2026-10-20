package com.calendar.users.exception;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.server.resource.web.access.server.BearerTokenServerAccessDeniedHandler;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Answers an authenticated request the security chain refuses with our 403 problem,
 * {@code ACCESS_DENIED}, keeping the {@code WWW-Authenticate} challenge Spring's
 * {@link BearerTokenServerAccessDeniedHandler} sets.
 */
@Component
public class ProblemAccessDeniedHandler implements ServerAccessDeniedHandler {

    private final BearerTokenServerAccessDeniedHandler challenge = new BearerTokenServerAccessDeniedHandler();
    private final ProblemResponseWriter problems;

    public ProblemAccessDeniedHandler(ProblemResponseWriter problems) {
        this.problems = problems;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, AccessDeniedException denied) {
        return challenge.handle(HeadersOnlyExchange.of(exchange), denied)
                .then(Mono.defer(() -> problems.write(exchange,
                        new BusinessException(BusinessErrorCode.ACCESS_DENIED))));
    }
}
