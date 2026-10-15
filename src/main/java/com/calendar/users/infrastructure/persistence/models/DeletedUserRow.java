package com.calendar.users.infrastructure.persistence.models;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Projection of a tombstone for the keyset listing. */
public record DeletedUserRow(UUID id, OffsetDateTime deletedAt) {}
