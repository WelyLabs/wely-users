package com.calendar.users.domain.models;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

public record BusinessUser(

        Long id,
        String profilePicUrl,
        LocalDateTime joinedDate
) {}
