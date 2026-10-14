package com.calendar.users.domain.models;

/**
 * How a user set the app up for themselves. Kept with their account rather than in one browser, so
 * the choice follows them from their phone to their computer.
 *
 * <p>No theme means the device's: what the app did before anyone could choose.
 */
public record UserPreferences(Theme theme) {

    public static final UserPreferences DEFAULT = new UserPreferences(Theme.SYSTEM);

    public UserPreferences {
        theme = theme == null ? Theme.SYSTEM : theme;
    }
}
