package com.calendar.users.domain.models;

import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class BusinessUserTest {

    @Test
    void businessUser_ShouldHoldData() {
        // Given
        UUID id = UUID.randomUUID();
        String username = "testuser";
        Integer hashtag = 1234;
        String firstName = "First";
        String lastName = "Last";
        String profilePicUrl = "http://url.com";
        LocalDateTime joinedDate = LocalDateTime.now();

        // When
        BusinessUser user = new BusinessUser(id, username, hashtag, firstName, lastName, profilePicUrl, joinedDate);

        // Then
        assertEquals(id, user.id());
        assertEquals(username, user.userName());
        assertEquals(hashtag, user.hashtag());
        assertEquals(firstName, user.firstName());
        assertEquals(lastName, user.lastName());
        assertEquals(profilePicUrl, user.profilePicUrl());
        assertEquals(joinedDate, user.joinedDate());
    }

    @Test
    void businessUser_Equality() {
        UUID id = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();
        BusinessUser user1 = new BusinessUser(id, "u", 1, "F", "L", "url", now);
        BusinessUser user2 = new BusinessUser(id, "u", 1, "F", "L", "url", now);

        assertEquals(user1, user2);
        assertEquals(user1.hashCode(), user2.hashCode());
        assertTrue(user1.toString().contains("u"));
    }
}
