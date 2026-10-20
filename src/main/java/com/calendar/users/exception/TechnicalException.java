package com.calendar.users.exception;

import lombok.Getter;

/**
 * A dependency this service needs is unavailable.
 *
 * <p>Distinct from {@link BusinessException} because the two call for different responses:
 * a business failure means the caller should change the request, a technical one means the
 * caller should retry and someone should look at an incident.
 *
 * <p>When the failure comes from an underlying error — a Keycloak, PostgreSQL, Kafka or
 * WebClient exception — pass it as the cause, so the incident log shows the real failure and
 * not only this wrapper.
 */
@Getter
public class TechnicalException extends RuntimeException {

    private final TechnicalErrorCode errorCode;

    public TechnicalException(TechnicalErrorCode errorCode) {
        super(errorCode.getDetail());
        this.errorCode = errorCode;
    }

    public TechnicalException(TechnicalErrorCode errorCode, Throwable cause) {
        super(errorCode.getDetail(), cause);
        this.errorCode = errorCode;
    }
}
