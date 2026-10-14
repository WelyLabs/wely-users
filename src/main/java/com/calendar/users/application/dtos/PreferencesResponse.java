package com.calendar.users.application.dtos;

import com.calendar.users.domain.models.Theme;

/** The preferences as stored: SYSTEM for a user who never chose. */
public record PreferencesResponse(Theme theme) {}
