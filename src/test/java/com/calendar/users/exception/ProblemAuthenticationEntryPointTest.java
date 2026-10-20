package com.calendar.users.exception;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import reactor.test.StepVerifier;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class ProblemAuthenticationEntryPointTest {

    private final ProblemAuthenticationEntryPoint entryPoint = new ProblemAuthenticationEntryPoint(
            new ProblemResponseWriter(new GlobalErrorHandler(new TechnicalErrorCounter(new SimpleMeterRegistry())), ServerCodecConfigurer.create()));

    private static MockServerWebExchange exchange() {
        return MockServerWebExchange.from(MockServerHttpRequest.get("/user-service/profile"));
    }

    @Test
    void commence_shouldAnswerOurProblemWithTheBearerChallenge_whenNoTokenIsSent() {
        MockServerWebExchange exchange = exchange();

        StepVerifier.create(entryPoint.commence(exchange, new AuthenticationCredentialsNotFoundException("no token")))
                .expectComplete()
                .verify(Duration.ofSeconds(10));

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(exchange.getResponse().getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("Bearer");
        assertThat(exchange.getResponse().getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(exchange.getResponse().getBodyAsString().block(Duration.ofSeconds(10)))
                .contains("\"code\":\"USR-REQ-001\"")
                .contains("\"requestId\":\"" + exchange.getRequest().getId() + "\"");
    }

    @Test
    void commence_shouldKeepTheInvalidTokenDescription_whenTheTokenIsRejected() {
        MockServerWebExchange exchange = exchange();

        StepVerifier.create(entryPoint.commence(exchange, new InvalidBearerTokenException("Jwt expired")))
                .expectComplete()
                .verify(Duration.ofSeconds(10));

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(exchange.getResponse().getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE))
                .startsWith("Bearer")
                .contains("error=\"invalid_token\"");
        assertThat(exchange.getResponse().getBodyAsString().block(Duration.ofSeconds(10)))
                .contains("\"code\":\"USR-REQ-001\"");
    }
}
