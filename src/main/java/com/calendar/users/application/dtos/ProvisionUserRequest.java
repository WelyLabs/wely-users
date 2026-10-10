package com.calendar.users.application.dtos;

import jakarta.validation.constraints.NotBlank;

/** Sent by Keycloak's registration step, once the Keycloak user exists. */
public record ProvisionUserRequest(
        @NotBlank String keycloakId,
        @NotBlank String username,
        String firstName,
        String lastName
) {}
