package com.calendar.users.infrastructure.persistence.models;

/** Projection of app_user for the user's preferences: theme is one of LIGHT, DARK, SYSTEM. */
public record UserPreferencesRow(String theme) {}
