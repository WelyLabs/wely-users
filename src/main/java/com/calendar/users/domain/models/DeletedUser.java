package com.calendar.users.domain.models;

import java.time.Instant;
import java.util.UUID;

/** A tombstone as other services see it; also the keyset cursor of the listing. */
public record DeletedUser(UUID userId, Instant deletedAt) {
}
