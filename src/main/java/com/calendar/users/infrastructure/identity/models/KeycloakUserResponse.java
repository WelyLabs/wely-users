package com.calendar.users.infrastructure.identity.models;

public record KeycloakUserResponse(
        String username,
        String firstName,
        String lastName,
        String email,
        boolean emailVerified
) {}
