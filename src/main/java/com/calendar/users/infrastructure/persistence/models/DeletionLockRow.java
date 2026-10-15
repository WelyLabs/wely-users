package com.calendar.users.infrastructure.persistence.models;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Projection of the app_user row locked by the deletion transaction. */
public record DeletionLockRow(UUID id, String keycloakId, OffsetDateTime deletedAt) {}
