package com.calendar.users.exception;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ProblemDetail;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import jakarta.validation.Valid;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalErrorHandlerTest {

    private WebTestClient client;
    private SimpleMeterRegistry meterRegistry;

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

        @GetMapping("/user-deleted")
        Mono<Void> userDeleted() {
            return Mono.error(new BusinessException(BusinessErrorCode.USER_DELETED));
        }

        @GetMapping("/reauthenticate")
        Mono<Void> reauthenticate() {
            return Mono.error(new BusinessException(BusinessErrorCode.REAUTHENTICATION_REQUIRED));
        }

        @GetMapping("/gone")
        Mono<Void> gone() {
            return Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND));
        }

        @GetMapping("/not-allowed")
        Mono<Void> notAllowed() {
            return Mono.error(new ResponseStatusException(HttpStatus.METHOD_NOT_ALLOWED));
        }
    }

    record UpdateRequest(@NotBlank String userName) {
    }

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        client = WebTestClient.bindToController(new TestController())
                .controllerAdvice(new GlobalErrorHandler(meterRegistry))
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
                .jsonPath("$.code").isEqualTo(GlobalErrorHandler.VALIDATION_FAILED_CODE)
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
                .jsonPath("$.code").isEqualTo(GlobalErrorHandler.UNEXPECTED_FAILURE_CODE)
                .jsonPath("$.detail").isEqualTo("The request could not be completed.")
                // The exception message carried a connection string with a password:
                // none of that may cross the HTTP boundary.
                .jsonPath("$.detail").value(detail -> {
                    if (detail.toString().contains("hunter2") || detail.toString().contains("postgres")) {
                        throw new AssertionError("the original message leaked into the response");
                    }
                });
    }

    @Test
    @DisplayName("an unknown path keeps its 404 instead of becoming a 500")
    void handleResponseStatusException_shouldKeepTheOriginalStatus() {
        // The regression this guards: @ExceptionHandler(Exception.class) also catches
        // ResponseStatusException, so before USR-REQ-000 existed every unknown path answered
        // 500. The service reported a fault of its own for a request it had handled correctly.
        client.get().uri("/gone")
                .exchange()
                .expectStatus().isNotFound()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(404)
                .jsonPath("$.title").isEqualTo("Not Found")
                .jsonPath("$.code").isEqualTo(GlobalErrorHandler.REQUEST_REFUSED_CODE);
    }

    @Test
    @DisplayName("a rejected method keeps its 405")
    void handleResponseStatusException_shouldCarryAnyStatusThrough() {
        client.get().uri("/not-allowed")
                .exchange()
                .expectStatus().isEqualTo(405)
                .expectBody()
                .jsonPath("$.status").isEqualTo(405)
                .jsonPath("$.title").isEqualTo("Method Not Allowed")
                .jsonPath("$.code").isEqualTo(GlobalErrorHandler.REQUEST_REFUSED_CODE);
    }

    @Test
    @DisplayName("a deleted account answers 410 Gone with its own code")
    void handleBusinessException_shouldAnswerGone_whenTheUserDeletedTheirAccount() {
        client.get().uri("/user-deleted")
                .exchange()
                .expectStatus().isEqualTo(410)
                .expectBody()
                .jsonPath("$.status").isEqualTo(410)
                .jsonPath("$.code").isEqualTo("USR-BUS-012");
    }

    @Test
    @DisplayName("a stale sign-in answers 403, never 401: the web app turns a 401 into a plain login")
    void handleBusinessException_shouldAnswerForbidden_whenReauthenticationIsRequired() {
        client.get().uri("/reauthenticate")
                .exchange()
                .expectStatus().isForbidden()
                .expectBody()
                .jsonPath("$.code").isEqualTo("USR-BUS-010");
    }

    // --- request id ------------------------------------------------------------

    private void assertCarriesARequestId(String uri) {
        client.get().uri(uri)
                .exchange()
                .expectBody()
                .jsonPath("$.requestId").value(requestId -> assertThat(requestId.toString()).isNotBlank());
    }

    @Test
    @DisplayName("every problem carries the request id that ends its log lines")
    void handleBusinessException_shouldCarryTheRequestId() {
        assertCarriesARequestId("/business-error");
    }

    @Test
    void handleTechnicalException_shouldCarryTheRequestId() {
        assertCarriesARequestId("/technical-error");
    }

    @Test
    void handleUnexpectedException_shouldCarryTheRequestId() {
        assertCarriesARequestId("/generic-error");
    }

    @Test
    void handleResponseStatusException_shouldCarryTheRequestId() {
        assertCarriesARequestId("/gone");
    }

    @Test
    void handleValidationException_shouldCarryTheRequestId() {
        client.post().uri("/validated")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"userName\":\"\"}")
                .exchange()
                .expectBody()
                .jsonPath("$.requestId").value(requestId -> assertThat(requestId.toString()).isNotBlank());
    }

    // --- technical error counter -----------------------------------------------

    private double technicalErrors(String code) {
        var counter = meterRegistry.find(GlobalErrorHandler.TECHNICAL_ERRORS_METRIC).tag(GlobalErrorHandler.CODE_TAG, code).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    @DisplayName("a dependency failure is counted under its own code")
    void handleTechnicalException_shouldCountTheFailureUnderItsCode() {
        client.get().uri("/technical-error").exchange().expectStatus().isEqualTo(502);
        client.get().uri("/technical-error").exchange().expectStatus().isEqualTo(502);

        assertThat(technicalErrors(TechnicalErrorCode.DATABASE_ERROR.getCode())).isEqualTo(2.0);
        assertThat(technicalErrors(GlobalErrorHandler.UNEXPECTED_FAILURE_CODE)).isZero();
    }

    @Test
    @DisplayName("an unexpected failure is counted as USR-TEC-000")
    void handleUnexpectedException_shouldCountTheFailureAsUnexpected() {
        client.get().uri("/generic-error").exchange().expectStatus().is5xxServerError();

        assertThat(technicalErrors(GlobalErrorHandler.UNEXPECTED_FAILURE_CODE)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("a business failure is not an incident and is not counted")
    void handleBusinessException_shouldNotCountATechnicalError() {
        client.get().uri("/business-error").exchange().expectStatus().isNotFound();
        client.get().uri("/gone").exchange().expectStatus().isNotFound();

        assertThat(meterRegistry.find(GlobalErrorHandler.TECHNICAL_ERRORS_METRIC).counters()).isEmpty();
    }

    // --- toProblem: failures outside controllers ---------------------------------

    private final MockServerWebExchange exchange =
            MockServerWebExchange.from(MockServerHttpRequest.get("/user-service/profile"));

    private ProblemDetail toProblem(Throwable error) {
        return new GlobalErrorHandler(meterRegistry).toProblem(error, exchange);
    }

    @Test
    void toProblem_shouldMapABusinessFailureToItsCode() {
        ProblemDetail problem = toProblem(new BusinessException(BusinessErrorCode.ACCESS_DENIED));

        assertThat(problem.getStatus()).isEqualTo(403);
        assertThat(problem.getProperties())
                .containsEntry("code", "USR-REQ-002")
                .containsEntry(GlobalErrorHandler.REQUEST_ID_PROPERTY, exchange.getRequest().getId());
    }

    @Test
    void toProblem_shouldMapATechnicalFailureToItsCode_andCountIt() {
        ProblemDetail problem = toProblem(new TechnicalException(TechnicalErrorCode.SOCIAL_ERROR));

        assertThat(problem.getStatus()).isEqualTo(502);
        assertThat(problem.getProperties()).containsEntry("code", "USR-TEC-005");
        assertThat(technicalErrors("USR-TEC-005")).isEqualTo(1.0);
    }

    @Test
    void toProblem_shouldKeepTheFrameworkStatus_whenTheFailureIsAResponseStatus() {
        ProblemDetail problem = toProblem(new ResponseStatusException(HttpStatus.NOT_FOUND));

        assertThat(problem.getStatus()).isEqualTo(404);
        assertThat(problem.getProperties()).containsEntry("code", GlobalErrorHandler.REQUEST_REFUSED_CODE);
    }

    @Test
    void toProblem_shouldAnswerTheUnexpectedProblem_andCountIt_whenTheFailureIsUnknown() {
        ProblemDetail problem = toProblem(new AssertionError("not even an Exception"));

        assertThat(problem.getStatus()).isEqualTo(500);
        assertThat(problem.getProperties()).containsEntry("code", GlobalErrorHandler.UNEXPECTED_FAILURE_CODE);
        assertThat(technicalErrors(GlobalErrorHandler.UNEXPECTED_FAILURE_CODE)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("the advice runs before Boot's problem-details advice, which sits at order 0")
    void globalErrorHandler_shouldBeOrderedFirst() {
        assertThat(GlobalErrorHandler.class.getAnnotation(Order.class).value()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
    }

    @Test
    void handlerCodes_shouldListTheThreeLiteralCodes() {
        assertThat(GlobalErrorHandler.HANDLER_CODES).containsExactly(
                GlobalErrorHandler.VALIDATION_FAILED_CODE,
                GlobalErrorHandler.REQUEST_REFUSED_CODE,
                GlobalErrorHandler.UNEXPECTED_FAILURE_CODE);
    }
}
