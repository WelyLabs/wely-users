package com.calendar.users.exception;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.test.StepVerifier;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class ProblemWebExceptionHandlerTest {

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final ProblemWebExceptionHandler handler = new ProblemWebExceptionHandler(
            new ProblemResponseWriter(new GlobalErrorHandler(new TechnicalErrorCounter(meterRegistry)), ServerCodecConfigurer.create()));

    @Test
    void handle_shouldAnswerOurProblemAndCountIt_whenAFilterFailedTechnically() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/user-service/profile"));

        StepVerifier.create(handler.handle(exchange, new TechnicalException(TechnicalErrorCode.KEYCLOAK_ERROR)))
                .expectComplete()
                .verify(Duration.ofSeconds(10));

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(exchange.getResponse().getBodyAsString().block(Duration.ofSeconds(10)))
                .contains("\"code\":\"USR-TEC-001\"")
                .contains("\"requestId\"");
        assertThat(meterRegistry.counter(GlobalErrorHandler.TECHNICAL_ERRORS_METRIC,
                GlobalErrorHandler.CODE_TAG, "USR-TEC-001",
                GlobalErrorHandler.SOURCE_TAG, GlobalErrorHandler.SOURCE_HTTP).count()).isEqualTo(1.0);
    }

    @Test
    void handle_shouldPassTheFailureOn_whenTheResponseIsAlreadyCommitted() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/user-service/profile"));
        exchange.getResponse().setComplete().block(Duration.ofSeconds(10));
        IllegalStateException failure = new IllegalStateException("too late");

        StepVerifier.create(handler.handle(exchange, failure))
                .expectErrorSatisfies(error -> assertThat(error).isSameAs(failure))
                .verify(Duration.ofSeconds(10));
    }

    @Test
    void handle_shouldRunBeforeBootsErrorHandler() {
        // Boot's DefaultErrorWebExceptionHandler is at -1 and answers in Boot's own JSON.
        assertThat(ProblemWebExceptionHandler.class.getAnnotation(Order.class).value()).isLessThan(-1);
    }
}
