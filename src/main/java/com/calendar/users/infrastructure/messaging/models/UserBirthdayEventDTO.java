package com.calendar.users.infrastructure.messaging.models;

import java.util.UUID;

/** USER_BIRTHDAY on the wire: the user's birthday without its year, and the name to show it under. */
public record UserBirthdayEventDTO(
        UUID userId,
        String displayName,
        int month,
        int day
) {
}
