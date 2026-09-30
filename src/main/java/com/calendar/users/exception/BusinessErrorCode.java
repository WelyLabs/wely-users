package com.calendar.users.exception;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * Business outcomes this service refuses on, each with a stable code.
 *
 * <p>{@code code} is the contract: it never changes, and it is what a client branches on.
 * {@code title} and {@code detail} are for whoever reads the response or the logs, and are
 * in English — the user-facing wording belongs to the frontend, which knows the reader's
 * language. Nothing in the UI displays these strings.
 */
@Getter
@AllArgsConstructor
public enum BusinessErrorCode {

    USER_NOT_FOUND(
            "USR-BUS-001",
            "User not found",
            "No user matches the given identifier.",
            HttpStatus.NOT_FOUND),

    USER_ALREADY_EXISTS(
            "USR-BUS-002",
            "User already exists",
            "A user already exists for this identity.",
            HttpStatus.CONFLICT),

    HASHTAG_UNAVAILABLE(
            "USR-BUS-003",
            "No handle available",
            "Every hashtag tried for this username is taken; retry or pick another name.",
            HttpStatus.CONFLICT);

    private final String code;
    private final String title;
    private final String detail;
    private final HttpStatus httpStatus;
}
