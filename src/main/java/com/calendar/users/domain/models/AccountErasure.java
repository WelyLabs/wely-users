package com.calendar.users.domain.models;

/** Outcome of turning an account into a tombstone. */
public enum AccountErasure {
    ERASED,
    ALREADY_ERASED,
    UNKNOWN_USER
}
