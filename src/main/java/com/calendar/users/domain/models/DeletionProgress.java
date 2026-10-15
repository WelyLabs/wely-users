package com.calendar.users.domain.models;

import java.util.UUID;

/** What one worker pass did for a deletion job; error is a code, never a message. */
public record DeletionProgress(UUID userId, Status status, int attempts, String error) {

    public enum Status {
        COMPLETED,
        WAITING,
        FAILED
    }
}
