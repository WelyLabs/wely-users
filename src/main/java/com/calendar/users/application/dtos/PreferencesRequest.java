package com.calendar.users.application.dtos;

import com.calendar.users.domain.models.Theme;
import jakarta.validation.constraints.NotNull;

/** The preferences as the settings screen sends them; a theme outside the three is refused as unreadable. */
public record PreferencesRequest(@NotNull Theme theme) {}
