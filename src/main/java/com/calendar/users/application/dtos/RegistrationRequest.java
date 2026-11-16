package com.calendar.users.application.dtos;

import java.time.LocalDate;

/**
 * A pass of the signup completion: only the fields the profile misses, each optional. The domain
 * validates what is sent and refuses an empty pass.
 */
public record RegistrationRequest(LocalDate birthDate, String firstName, String lastName) {}
