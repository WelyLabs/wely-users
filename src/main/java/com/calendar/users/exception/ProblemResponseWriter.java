package com.calendar.users.exception;

import org.springframework.core.ResolvableType;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.codec.HttpMessageWriter;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * Writes a failure as our problem straight onto the response, for the places that have no
 * controller to return one from: the security chain, web filters, the last-resort
 * {@link ProblemWebExceptionHandler}.
 *
 * <p>The mapping is {@link GlobalErrorHandler#toProblem}, so the code, status, log line and
 * counter are those a controller failure would get. The body goes through the application's
 * own codecs, so it is serialized exactly like the advice's.
 */
@Component
public class ProblemResponseWriter {

    private static final ResolvableType PROBLEM_TYPE = ResolvableType.forClass(ProblemDetail.class);

    private final GlobalErrorHandler handler;
    private final List<HttpMessageWriter<?>> writers;

    public ProblemResponseWriter(GlobalErrorHandler handler, ServerCodecConfigurer codecs) {
        this.handler = handler;
        this.writers = codecs.getWriters();
    }

    public Mono<Void> write(ServerWebExchange exchange, Throwable error) {
        ProblemDetail problem = handler.toProblem(error, exchange);
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatusCode.valueOf(problem.getStatus()));
        response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);

        return problemWriter().write(Mono.just(problem), PROBLEM_TYPE, MediaType.APPLICATION_PROBLEM_JSON,
                response, Map.of());
    }

    @SuppressWarnings("unchecked")
    private HttpMessageWriter<ProblemDetail> problemWriter() {
        return (HttpMessageWriter<ProblemDetail>) writers.stream()
                .filter(writer -> writer.canWrite(PROBLEM_TYPE, MediaType.APPLICATION_PROBLEM_JSON))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("no codec writes application/problem+json"));
    }
}
