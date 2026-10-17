package com.calendar.users.domain.models;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.MonthDay;
import java.util.UUID;

/** A user's own profile; birthDate is theirs to see, friends only get its day and month. */
public record BusinessUser(

        UUID id,
        String userName,
        Integer hashtag,
        String firstName,
        String lastName,
        String profilePicUrl,
        LocalDateTime joinedDate,
        LocalDate birthDate,
        boolean registrationCompleted
) {

    /** A user who has not completed their signup yet: no birth date. */
    public BusinessUser(UUID id, String userName, Integer hashtag, String firstName, String lastName,
                        String profilePicUrl, LocalDateTime joinedDate) {
        this(id, userName, hashtag, firstName, lastName, profilePicUrl, joinedDate, null, false);
    }

    /** The name friends know the user by: the first name when set, the username otherwise. */
    public String displayName() {
        return firstName == null || firstName.isBlank() ? userName : firstName;
    }

    /** The birth date without its year, or null when unknown. */
    public MonthDay birthday() {
        return birthDate == null ? null : MonthDay.from(birthDate);
    }
}
