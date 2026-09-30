package com.calendar.users.exception;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * Failures of a dependency rather than of the request.
 *
 * <p>Each names which dependency broke, so an incident can be routed without reading the
 * logs. All of them answer 502 rather than 500: the request was valid and this service is
 * running — something it depends on is not, and 502 says exactly that.
 */
@Getter
@AllArgsConstructor
public enum TechnicalErrorCode {

    KEYCLOAK_ERROR(
            "USR-TEC-001",
            "Identity provider unavailable",
            "The identity provider could not be reached.",
            HttpStatus.BAD_GATEWAY),

    DATABASE_ERROR(
            "USR-TEC-002",
            "Database unavailable",
            "The user store could not be reached.",
            HttpStatus.BAD_GATEWAY),

    KAFKA_ERROR(
            "USR-TEC-003",
            "Event broker unavailable",
            "The event could not be published.",
            HttpStatus.BAD_GATEWAY);

    private final String code;
    private final String title;
    private final String detail;
    private final HttpStatus httpStatus;
}
