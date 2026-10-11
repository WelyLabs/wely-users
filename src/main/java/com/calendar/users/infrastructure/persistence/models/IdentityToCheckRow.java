package com.calendar.users.infrastructure.persistence.models;

import java.util.UUID;

/** Projection of app_user for the orphan check. */
public record IdentityToCheckRow(UUID id, String keycloakId) {}
