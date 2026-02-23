package com.calendar.users.infrastructure.persistence.models.entities;

import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class UserEntityTest {

    @Test
    void userEntity_GettersAndSetters() {
        // Given
        UserEntity entity = new UserEntity();
        UUID id = UUID.randomUUID();
        String kcId = "kc-123";
        String userName = "user";
        Integer hashtag = 1234;
        String firstName = "First";
        String lastName = "Last";
        String profilePicUrl = "url";
        LocalDateTime joinedDate = LocalDateTime.now();

        // When
        entity.setId(id);
        entity.setKeycloakId(kcId);
        entity.setUserName(userName);
        entity.setHashtag(hashtag);
        entity.setFirstName(firstName);
        entity.setLastName(lastName);
        entity.setProfilePicUrl(profilePicUrl);
        entity.setJoinedDate(joinedDate);

        // Then
        assertEquals(id, entity.getId());
        assertEquals(kcId, entity.getKeycloakId());
        assertEquals(userName, entity.getUserName());
        assertEquals(hashtag, entity.getHashtag());
        assertEquals(firstName, entity.getFirstName());
        assertEquals(lastName, entity.getLastName());
        assertEquals(profilePicUrl, entity.getProfilePicUrl());
        assertEquals(joinedDate, entity.getJoinedDate());
    }
}
