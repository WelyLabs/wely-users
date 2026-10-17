package com.calendar.users.domain.models;

import java.time.MonthDay;

import java.time.LocalDate;

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

    @Test
    void businessUser_shouldHaveNoBirthDateAndAnIncompleteSignup_whenBuiltWithoutThem() {
        BusinessUser user = new BusinessUser(UUID.randomUUID(), "u", 1, "F", "L", null, LocalDateTime.now());

        assertNull(user.birthDate());
        assertFalse(user.registrationCompleted());
    }

    @Test
    void displayName_shouldBeTheFirstName_whenSet() {
        assertEquals("Léa", new BusinessUser(UUID.randomUUID(), "lea", 1, "Léa", null, null, null).displayName());
    }

    @Test
    void displayName_shouldBeTheUserName_whenTheFirstNameIsMissingOrBlank() {
        assertEquals("lea", new BusinessUser(UUID.randomUUID(), "lea", 1, null, null, null, null).displayName());
        assertEquals("lea", new BusinessUser(UUID.randomUUID(), "lea", 1, "  ", null, null, null).displayName());
    }

    @Test
    void birthday_shouldDropTheYear_whenABirthDateIsSet() {
        BusinessUser user = new BusinessUser(UUID.randomUUID(), "u", 1, null, null, null, null,
                LocalDate.of(1995, 3, 12), true);

        assertEquals(MonthDay.of(3, 12), user.birthday());
    }

    @Test
    void birthday_shouldBeNull_whenNoBirthDateIsSet() {
        assertNull(new BusinessUser(UUID.randomUUID(), "u", 1, null, null, null, null).birthday());
    }
}
