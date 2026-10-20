package com.calendar.users.exception;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;

/**
 * Answers our problem for failures that never reach a controller: a web filter that throws,
 * an error before handler mapping.
 *
 * <p>Ordered at -2, ahead of Boot's {@code DefaultErrorWebExceptionHandler} (-1), which would
 * otherwise answer with Boot's own error JSON — no {@code code}, no {@code requestId}. A
 * response already committed cannot be rewritten, so the failure is passed on untouched.
 */
@Component
@Order(-2)
public class ProblemWebExceptionHandler implements WebExceptionHandler {

    private final ProblemResponseWriter problems;

    public ProblemWebExceptionHandler(ProblemResponseWriter problems) {
        this.problems = problems;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        if (exchange.getResponse().isCommitted()) {
            return Mono.error(ex);
        }
        return problems.write(exchange, ex);
    }
}
