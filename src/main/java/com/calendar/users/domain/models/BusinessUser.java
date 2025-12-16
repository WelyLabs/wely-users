package com.calendar.users.domain.models;

import java.time.LocalDateTime;

public record BusinessUser(

        Long id,
        String userName,
        String firstName,
        String lastName,
        String profilePicUrl,
        LocalDateTime joinedDate
) {}
