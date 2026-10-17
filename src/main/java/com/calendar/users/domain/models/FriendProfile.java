package com.calendar.users.domain.models;

import java.time.LocalDateTime;
import java.time.MonthDay;
import java.util.List;
import java.util.UUID;

/** What a friend may see of a user: their names, since when they are here, what they wrote, and their birthday without its year. */
public record FriendProfile(
        UUID id,
        String userName,
        Integer hashtag,
        String firstName,
        String lastName,
        LocalDateTime joinedDate,
        String bio,
        List<String> interests,
        MonthDay birthday
) {

    public FriendProfile {
        interests = interests == null ? List.of() : List.copyOf(interests);
    }

    public static FriendProfile of(BusinessUser user, UserAbout about) {
        return new FriendProfile(user.id(), user.userName(), user.hashtag(), user.firstName(), user.lastName(),
                user.joinedDate(), about.bio(), about.interests(), user.birthday());
    }
}
