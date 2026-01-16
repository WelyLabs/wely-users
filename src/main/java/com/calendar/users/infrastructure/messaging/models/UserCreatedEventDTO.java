package com.calendar.users.infrastructure.messaging.models;

import java.util.UUID;

public record UserCreatedEventDTO(
        UUID userId,
        String userName,
        Integer hashtag,
        String profilePicUrl
) {
}
