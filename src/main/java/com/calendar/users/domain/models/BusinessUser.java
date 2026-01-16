package com.calendar.users.domain.models;

import java.time.LocalDateTime;
import java.util.UUID;

public record BusinessUser(

        UUID id,
        String userName,
        Integer hashtag,
        String firstName,
        String lastName,
        String profilePicUrl,
        LocalDateTime joinedDate
) {}
