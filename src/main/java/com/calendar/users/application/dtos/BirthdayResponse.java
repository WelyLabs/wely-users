package com.calendar.users.application.dtos;

/** A birthday as friends see it: the day and month, never the year. */
public record BirthdayResponse(int month, int day) {}
