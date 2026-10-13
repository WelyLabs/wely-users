package com.calendar.users.infrastructure.persistence.models;

/** Projection of app_user for the profile's presentation: interests is a text[] column. */
public record UserAboutRow(String bio, String[] interests) {}
