package com.calendar.users.domain.models;

import java.util.UUID;

/** A user whose Keycloak account has not been confirmed to exist yet. */
public record IdentityToCheck(UUID userId, String keycloakId) {}
