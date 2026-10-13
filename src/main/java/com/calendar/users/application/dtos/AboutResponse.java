package com.calendar.users.application.dtos;

import java.util.List;

/** The profile's presentation as stored: no bio is null, no interest an empty list. */
public record AboutResponse(String bio, List<String> interests) {}
