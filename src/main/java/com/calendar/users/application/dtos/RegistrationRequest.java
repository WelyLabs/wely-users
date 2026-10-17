package com.calendar.users.application.dtos;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/** The signup completion form; later steps add their fields here. The age rule is the domain's. */
public record RegistrationRequest(@NotNull LocalDate birthDate) {}
