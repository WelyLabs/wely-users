package com.calendar.users.exception;

import jakarta.validation.ValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GlobalErrorHandlerTest {

    private WebTestClient webTestClient;

    @RestController
    static class TestController {
        @GetMapping("/business-error")
        public Mono<Void> businessError() {
            return Mono.error(new BusinessException(BusinessErrorCode.USER_NOT_FOUND));
        }

        @GetMapping("/technical-error")
        public Mono<Void> technicalError() {
            return Mono.error(new TechnicalException(TechnicalErrorCode.DATABASE_ERROR));
        }

        @GetMapping("/validation-error")
        public Mono<Void> validationError() {
            return Mono.error(new ValidationException("Invalid data"));
        }

        @GetMapping("/generic-error")
        public Mono<Void> genericError() {
            return Mono.error(new RuntimeException("Generic error"));
        }
    }

    @BeforeEach
    void setUp() {
        webTestClient = WebTestClient.bindToController(new TestController())
                .controllerAdvice(new GlobalErrorHandler())
                .build();
    }

    @Test
    void handleBusinessException_ShouldReturnNotFound() {
        webTestClient.get()
                .uri("/business-error")
                .exchange()
                .expectStatus().isNotFound()
                .expectBody(ErrorResponse.class)
                .value(response -> {
                    assertEquals(BusinessErrorCode.USER_NOT_FOUND.getMessage(), response.message());
                    assertEquals(BusinessErrorCode.USER_NOT_FOUND.getCode(), response.errorCode());
                });
    }

    @Test
    void handleTechnicalException_ShouldReturnInternalServerError() {
        webTestClient.get()
                .uri("/technical-error")
                .exchange()
                .expectStatus().is5xxServerError()
                .expectBody(ErrorResponse.class)
                .value(response -> {
                    assertEquals(TechnicalErrorCode.DATABASE_ERROR.getMessage(), response.message());
                    assertEquals(TechnicalErrorCode.DATABASE_ERROR.getCode(), response.errorCode());
                });
    }

    @Test
    void handleValidationException_ShouldReturnBadRequest() {
        webTestClient.get()
                .uri("/validation-error")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody(ErrorResponse.class)
                .value(response -> {
                    assertEquals("Invalid data", response.message());
                    assertEquals("VALIDATION_ERROR", response.errorCode());
                });
    }
}
