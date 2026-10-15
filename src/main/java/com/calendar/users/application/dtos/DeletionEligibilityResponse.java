package com.calendar.users.application.dtos;

/** Whether the caller may delete their account; false only for a demo account. */
public record DeletionEligibilityResponse(boolean allowed) {}
