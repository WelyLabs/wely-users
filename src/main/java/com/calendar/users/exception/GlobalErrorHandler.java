package com.calendar.users.exception;

import jakarta.validation.ValidationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

@Slf4j
@ControllerAdvice
public class GlobalErrorHandler {

    @ExceptionHandler(BusinessException.class)
    public Mono<ResponseEntity<ErrorResponse>> handleBusinessException(BusinessException ex) {

        var error = ex.getErrorCode();

        log.warn("⚠️ [Business Error] Code: {} | Message: {}", error.getCode(), error.getMessage());

        return Mono.just(ResponseEntity
                .status(error.getHttpStatus())
                .body(new ErrorResponse(error.getMessage(), error.getCode(), LocalDateTime.now()))
        );
    }

    @ExceptionHandler(TechnicalException.class)
    public Mono<ResponseEntity<ErrorResponse>> handleTechnicalException(TechnicalException ex) {

        var error = ex.getErrorCode();

        log.warn("⚠️ [Technical Error] Code: {} | Message: {}", error.getCode(), error.getMessage());

        return Mono.just(ResponseEntity.status(500).body(new ErrorResponse(error.getMessage(), error.getCode(), LocalDateTime.now())));
    }

    @ExceptionHandler(ValidationException.class)
    public Mono<ResponseEntity<ErrorResponse>> handleValidationException(ValidationException ex) {

        log.warn("⚠️ [Technical Error] | Message: {}", ex.getMessage());

        return Mono.just(ResponseEntity
                .badRequest()
                .body(new ErrorResponse(
                        ex.getMessage(),
                        "VALIDATION_ERROR",
                        LocalDateTime.now())));
    }
}
