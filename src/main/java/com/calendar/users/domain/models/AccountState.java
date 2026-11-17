package com.calendar.users.domain.models;

/** Whether an existing user row is a live account, a live account whose deletion is scheduled, or a tombstone. */
public enum AccountState {
    ACTIVE,
    /** Still live and unchanged for everyone else; the erasure happens at the end of the grace period. */
    PENDING_DELETION,
    DELETED
}
