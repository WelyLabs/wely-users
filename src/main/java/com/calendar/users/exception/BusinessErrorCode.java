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
            HttpStatus.CONFLICT),

    /** The username is someone else's. The client shows it on the field. */
    USERNAME_TAKEN(
            "USR-BUS-004",
            "Username taken",
            "Another user already has this username.",
            HttpStatus.CONFLICT),

    /** The identity provider refused the profile: a rule the form did not catch. */
    PROFILE_INVALID(
            "USR-BUS-005",
            "Invalid profile",
            "The profile does not meet the identity provider's rules.",
            HttpStatus.BAD_REQUEST),

    PICTURE_INVALID(
            "USR-BUS-006",
            "Invalid picture",
            "The file is not a JPEG, PNG, GIF or BMP image, or is larger than 4000 by 4000 pixels.",
            HttpStatus.BAD_REQUEST),

    PICTURE_TOO_LARGE(
            "USR-BUS-007",
            "Picture too large",
            "The picture must be smaller than 5 MB.",
            HttpStatus.CONTENT_TOO_LARGE),

    ABOUT_INVALID(
            "USR-BUS-008",
            "Invalid profile presentation",
            "A bio holds up to 160 characters, and a profile up to 5 interests of up to 24 characters each.",
            HttpStatus.BAD_REQUEST),

    /** Another user's profile is shown to their friends only. */
    NOT_A_FRIEND(
            "USR-BUS-009",
            "Not a friend",
            "Only this user's friends can see their profile.",
            HttpStatus.FORBIDDEN),

    /** 403 rather than 401: the web app answers every 401 with a plain login, not a re-authentication. */
    REAUTHENTICATION_REQUIRED(
            "USR-BUS-010",
            "Recent sign-in required",
            "Sign in again, then confirm within 5 minutes.",
            HttpStatus.FORBIDDEN),

    ACCOUNT_PROTECTED(
            "USR-BUS-011",
            "Account protected",
            "This account cannot be deleted.",
            HttpStatus.FORBIDDEN),

    USER_DELETED(
            "USR-BUS-012",
            "User deleted",
            "This user deleted their account.",
            HttpStatus.GONE),

    DELETED_USERS_CURSOR_INVALID(
            "USR-BUS-013",
            "Invalid cursor",
            "The cursor was not issued by this service.",
            HttpStatus.BAD_REQUEST),

    /** ACCOUNT_DELETION_ENABLED is not true on this deployment. */
    ACCOUNT_DELETION_DISABLED(
            "USR-BUS-014",
            "Account deletion disabled",
            "Account deletion is not available on this deployment.",
            HttpStatus.FORBIDDEN);

    private final String code;
    private final String title;
    private final String detail;
    private final HttpStatus httpStatus;
}
