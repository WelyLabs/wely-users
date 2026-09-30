package com.calendar.users.exception;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import jakarta.validation.Valid;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import jakarta.validation.constraints.NotBlank;
import reactor.core.publisher.Mono;

class GlobalErrorHandlerTest {

    private WebTestClient client;

    @RestController
    static class TestController {

        @GetMapping("/business-error")
        Mono<Void> businessError() {
            return Mono.error(new BusinessException(BusinessErrorCode.USER_NOT_FOUND));
        }

        @GetMapping("/conflict-error")
        Mono<Void> conflictError() {
            return Mono.error(new BusinessException(BusinessErrorCode.USER_ALREADY_EXISTS));
        }

        @GetMapping("/technical-error")
        Mono<Void> technicalError() {
            return Mono.error(new TechnicalException(TechnicalErrorCode.DATABASE_ERROR));
        }

        @GetMapping("/generic-error")
        Mono<Void> genericError() {
            return Mono.error(new IllegalStateException("connection string is postgres://user:hunter2@host"));
        }

        /**
         * Binding a validated body is what raises WebExchangeBindException. A validated
         * @RequestParam raises HandlerMethodValidationException instead, which this handler
         * does not claim to cover.
         */
        @PostMapping("/validated")
        Mono<Void> validated(@Valid @RequestBody UpdateRequest body) {
            return Mono.empty();
        }
    }

    record UpdateRequest(@NotBlank String userName) {
    }

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToController(new TestController())
                .controllerAdvice(new GlobalErrorHandler())
                .validator(new LocalValidatorFactoryBean())
                .build();
    }

    @Test
    @DisplayName("a validation failure answers 400 and names the offending fields")
    void handleValidationException_shouldListTheFieldsThatFailed() {
        client.post().uri("/validated")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"userName\":\"\"}")
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.title").isEqualTo("Invalid request")
                .jsonPath("$.code").isEqualTo("USR-VAL-001")
                .jsonPath("$.errors.userName").exists();
    }

    @Test
    @DisplayName("a validation failure says which fields, not why the framework thinks so")
    void handleValidationException_shouldNotLeakTheFrameworkMessage() {
        // The detail stays generic for the same reason as the catch-all handler: framework
        // messages carry type names and binding internals that a client has no use for.
        client.post().uri("/validated")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"userName\":\"\"}")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.detail").isEqualTo("One or more fields are invalid.");
    }

    @Test
    @DisplayName("a business failure answers as application/problem+json")
    void handleBusinessException_shouldAnswerAsProblemDetail() {
        client.get().uri("/business-error")
                .exchange()
                .expectStatus().isNotFound()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(404)
                .jsonPath("$.title").isEqualTo("User not found")
                .jsonPath("$.detail").isEqualTo("No user matches the given identifier.")
                .jsonPath("$.code").isEqualTo("USR-BUS-001")
                .jsonPath("$.type").isEqualTo("https://welylabs.app/problems/usr-bus-001")
                .jsonPath("$.instance").isEqualTo("/business-error")
                .jsonPath("$.timestamp").exists();
    }

    @Test
    @DisplayName("the status comes from the error code, not from a default")
    void handleBusinessException_shouldUseTheStatusCarriedByTheCode() {
        client.get().uri("/conflict-error")
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody()
                .jsonPath("$.code").isEqualTo("USR-BUS-002");
    }

    @Test
    @DisplayName("an unavailable dependency answers 502, not 500")
    void handleTechnicalException_shouldAnswerBadGateway() {
        // 500 would say "this service is broken". 502 says "this service works, what it
        // depends on does not" — which is the truth, and points the diagnosis somewhere.
        client.get().uri("/technical-error")
                .exchange()
                .expectStatus().isEqualTo(502)
                .expectBody()
                .jsonPath("$.code").isEqualTo("USR-TEC-002")
                .jsonPath("$.title").isEqualTo("Database unavailable");
    }

    @Test
    @DisplayName("an unexpected error leaks nothing from the original message")
    void handleUnexpectedException_shouldNotLeakTheCause() {
        client.get().uri("/generic-error")
                .exchange()
                .expectStatus().is5xxServerError()
                .expectBody()
                .jsonPath("$.code").isEqualTo("USR-TEC-000")
                .jsonPath("$.detail").isEqualTo("The request could not be completed.")
                // The exception message carried a connection string with a password:
                // none of that may cross the HTTP boundary.
                .jsonPath("$.detail").value(detail -> {
                    if (detail.toString().contains("hunter2") || detail.toString().contains("postgres")) {
                        throw new AssertionError("the original message leaked into the response");
                    }
                });
    }
}
