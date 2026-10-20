package com.calendar.users.exception;

/** A failure reduced to a stable code, safe to store, log and count: never a message, a URL or an id. */
public final class ErrorCodes {

    private ErrorCodes() {
    }

    /** The error code of a business or technical failure, the class name of anything else. */
    public static String of(Throwable error) {
        if (error instanceof BusinessException business) {
            return business.getErrorCode().getCode();
        }
        if (error instanceof TechnicalException technical) {
            return technical.getErrorCode().getCode();
        }
        return error.getClass().getSimpleName();
    }
}
