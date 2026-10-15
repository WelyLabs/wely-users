package com.calendar.users.application.dtos;

import java.time.Instant;
import java.util.UUID;

/** One tombstone of the internal listing; deletedAt is written as an ISO-8601 UTC string. */
public record DeletedUserItem(UUID userId, Instant deletedAt) {}
