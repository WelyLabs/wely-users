package com.calendar.users.infrastructure.identity.models;

import com.fasterxml.jackson.annotation.JsonProperty;

/** The fields of an Auth0 user this service reads. */
public record Auth0UserResponse(
        String username,
        @JsonProperty("given_name") String givenName,
        @JsonProperty("family_name") String familyName,
        String email,
        @JsonProperty("email_verified") boolean emailVerified
) {}
