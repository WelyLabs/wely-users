package com.calendar.users.application.dtos;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/**
 * The signup completion form. The names are required unless the user already has both, which the
 * domain decides, as it does the age rule.
 */
public record RegistrationRequest(@NotNull LocalDate birthDate, String firstName, String lastName) {}
