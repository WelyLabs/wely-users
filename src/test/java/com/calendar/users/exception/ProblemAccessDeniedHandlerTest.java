package com.calendar.users.exception;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.access.AccessDeniedException;
import reactor.test.StepVerifier;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class ProblemAccessDeniedHandlerTest {

    private final ProblemAccessDeniedHandler handler = new ProblemAccessDeniedHandler(
            new ProblemResponseWriter(new GlobalErrorHandler(new TechnicalErrorCounter(new SimpleMeterRegistry())), ServerCodecConfigurer.create()));

    @Test
    void handle_shouldAnswerOurProblemWithTheBearerChallenge_whenTheRequestIsRefused() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.delete("/user-service/profile"));

        StepVerifier.create(handler.handle(exchange, new AccessDeniedException("denied")))
                .expectComplete()
                .verify(Duration.ofSeconds(10));

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange.getResponse().getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).startsWith("Bearer");
        assertThat(exchange.getResponse().getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(exchange.getResponse().getBodyAsString().block(Duration.ofSeconds(10)))
                .contains("\"code\":\"USR-REQ-002\"")
                .contains("\"requestId\":\"" + exchange.getRequest().getId() + "\"");
    }
}
