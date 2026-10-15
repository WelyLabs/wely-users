package com.calendar.users.application.dtos;

import java.util.List;
import java.util.UUID;

/** The subset of the requested ids whose account was deleted. */
public record DeletedUsersResponse(List<UUID> deletedIds) {}
