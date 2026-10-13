package com.calendar.users.application.dtos;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/** The users whose pictures a screen is about to show; one request for the whole screen. */
public record AvatarsRequest(@NotNull @Size(max = 100) List<UUID> userIds) {
}
