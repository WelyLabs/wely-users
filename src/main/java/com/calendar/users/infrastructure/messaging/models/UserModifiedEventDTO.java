package com.calendar.users.infrastructure.messaging.models;

import java.util.UUID;

/**
 * A user's profile after a change: the whole of it, not the fields that changed, so a consumer
 * can apply it without knowing what came before.
 */
public record UserModifiedEventDTO(
        UUID userId,
        String userName,
        Integer hashtag,
        String firstName,
        String lastName,
        String profilePicUrl
) {
}
