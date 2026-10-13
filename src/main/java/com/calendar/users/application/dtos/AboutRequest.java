package com.calendar.users.application.dtos;

import com.calendar.users.domain.models.UserAbout;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/** The profile's presentation as the form sends it; tidied and checked again by {@link UserAbout}. */
public record AboutRequest(
        @Size(max = UserAbout.MAX_BIO_LENGTH) String bio,
        @Size(max = UserAbout.MAX_INTERESTS) List<@NotNull @Size(max = UserAbout.MAX_INTEREST_LENGTH) String> interests
) {}
