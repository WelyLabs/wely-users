package com.calendar.users.infrastructure.models.dtos;

public record KeycloakUserResponse(
        String username,
        String firstName,
        String lastName,
        String email,
        boolean emailVerified
) {}
