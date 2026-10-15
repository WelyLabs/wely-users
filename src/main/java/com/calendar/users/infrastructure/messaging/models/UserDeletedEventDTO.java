package com.calendar.users.infrastructure.messaging.models;

import java.time.Instant;
import java.util.UUID;

/** USER_DELETED on the wire: {"userId": uuid, "deletedAt": ISO-8601 instant}; older records have no deletedAt. */
public record UserDeletedEventDTO(UUID userId, Instant deletedAt) {
}
