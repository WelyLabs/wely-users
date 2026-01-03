package com.calendar.users.infrastructure.messaging.models;

public record UserCreatedEventDTO(
        Long userId,
        String userName,
        Integer hashtag,
        String profilePicUrl
) {
}
