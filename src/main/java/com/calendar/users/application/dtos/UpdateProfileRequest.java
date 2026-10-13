package com.calendar.users.application.dtos;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** The same rules as the form: a username of letters, digits, dashes and underscores. */
public record UpdateProfileRequest(
        @NotBlank @Size(min = 3, max = 30) @Pattern(regexp = "^[a-zA-Z0-9_-]+$") String userName,
        @NotBlank @Size(max = 255) String firstName,
        @NotBlank @Size(max = 255) String lastName
) {
}
