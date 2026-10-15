package com.calendar.users.application.dtos;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/** The ids a screen could not name; one request for the whole screen, like AvatarsRequest. */
public record DeletedUsersRequest(@NotNull @Size(max = 100) List<@NotNull UUID> userIds) {}
