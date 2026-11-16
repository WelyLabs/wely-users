package com.calendar.users.application.dtos;

import jakarta.validation.constraints.NotBlank;

/** Sent by the Auth0 post-login Action for a user who has no businessId yet. */
public record ProvisionUserRequest(
        @NotBlank String subjectId,
        @NotBlank String username,
        String firstName,
        String lastName
) {}
