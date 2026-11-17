package com.calendar.users.application.dtos;

import java.time.Instant;

/**
 * Whether the caller may delete their account (false only for a demo account), and when their scheduled
 * erasure is due; dueAt is null when no deletion is scheduled.
 */
public record DeletionEligibilityResponse(boolean allowed, Instant dueAt) {}
