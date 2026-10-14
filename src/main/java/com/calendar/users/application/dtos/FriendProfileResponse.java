package com.calendar.users.application.dtos;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** A friend's profile: no bio is null, no interest an empty list. */
public record FriendProfileResponse(
        UUID id,
        String userName,
        Integer hashtag,
        String firstName,
        String lastName,
        LocalDateTime joinedDate,
        String bio,
        List<String> interests
) {}
