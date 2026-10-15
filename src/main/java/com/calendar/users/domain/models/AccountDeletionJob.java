package com.calendar.users.domain.models;

import java.time.Instant;
import java.util.UUID;

/** The identity and media cleanup still owed to a deleted account; subjectId is null once the identity is gone. */
public record AccountDeletionJob(
        UUID userId,
        String subjectId,
        Instant requestedAt,
        boolean identityDeleted,
        boolean mediaDeleted,
        int attempts
) {
}
