package com.calendar.users.domain.models;

/** Whether an existing user row is a live account or the tombstone of a deleted one. */
public enum AccountState {
    ACTIVE,
    DELETED
}
