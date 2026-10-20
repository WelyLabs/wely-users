package com.calendar.users.exception;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.test.StepVerifier;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class ProblemResponseWriterTest {

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final ProblemResponseWriter writer =
            new ProblemResponseWriter(new GlobalErrorHandler(meterRegistry), ServerCodecConfigurer.create());

    private static MockServerWebExchange exchange() {
        return MockServerWebExchange.from(MockServerHttpRequest.get("/user-service/profile"));
    }

    @Test
    void write_shouldAnswerTheTechnicalProblem_andCountIt_whenADependencyFailed() {
        MockServerWebExchange exchange = exchange();

        StepVerifier.create(writer.write(exchange, new TechnicalException(TechnicalErrorCode.DATABASE_ERROR)))
                .expectComplete()
                .verify(Duration.ofSeconds(10));

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(exchange.getResponse().getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(exchange.getResponse().getBodyAsString().block(Duration.ofSeconds(10)))
                .contains("\"code\":\"USR-TEC-002\"")
                .contains("\"requestId\":\"" + exchange.getRequest().getId() + "\"")
                .contains("\"instance\":\"/user-service/profile\"");
        assertThat(meterRegistry.counter(GlobalErrorHandler.TECHNICAL_ERRORS_METRIC,
                GlobalErrorHandler.CODE_TAG, "USR-TEC-002").count()).isEqualTo(1.0);
    }

    @Test
    void write_shouldAnswerTheBusinessStatus_whenTheFailureIsABusinessRefusal() {
        MockServerWebExchange exchange = exchange();

        StepVerifier.create(writer.write(exchange, new BusinessException(BusinessErrorCode.AUTHENTICATION_REQUIRED)))
                .expectComplete()
                .verify(Duration.ofSeconds(10));

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(exchange.getResponse().getBodyAsString().block(Duration.ofSeconds(10)))
                .contains("\"code\":\"USR-REQ-001\"");
    }

    @Test
    void write_shouldAnswerTheUnexpectedProblem_whenTheFailureIsUnknown() {
        MockServerWebExchange exchange = exchange();

        StepVerifier.create(writer.write(exchange, new IllegalStateException("token=s3cr3t")))
                .expectComplete()
                .verify(Duration.ofSeconds(10));

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(exchange.getResponse().getBodyAsString().block(Duration.ofSeconds(10)))
                .contains("\"code\":\"" + GlobalErrorHandler.UNEXPECTED_FAILURE_CODE + "\"")
                .doesNotContain("s3cr3t");
    }
}
