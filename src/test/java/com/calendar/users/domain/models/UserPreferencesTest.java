package com.calendar.users.domain.models;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UserPreferencesTest {

    @Test
    void constructor_shouldKeepTheChosenTheme() {
        assertThat(new UserPreferences(Theme.DARK).theme()).isEqualTo(Theme.DARK);
    }

    @Test
    void constructor_shouldFollowTheDevice_whenNoThemeIsGiven() {
        assertThat(new UserPreferences(null).theme()).isEqualTo(Theme.SYSTEM);
    }

    @Test
    void default_shouldFollowTheDevice() {
        assertThat(UserPreferences.DEFAULT.theme()).isEqualTo(Theme.SYSTEM);
    }
}
