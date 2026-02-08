package com.calendar.users.infrastructure.persistence.mappers;

import com.calendar.users.domain.models.BusinessUser;
import com.calendar.users.infrastructure.persistence.models.entities.UserEntity;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class UserEntityMapperTest {

    private final UserEntityMapper mapper = Mappers.getMapper(UserEntityMapper.class);

    @Test
    void toBusinessUser_ShouldMapAllFields() {
        // Given
        UUID id = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();
        UserEntity entity = new UserEntity();
        entity.setId(id);
        entity.setUserName("testuser");
        entity.setHashtag(1234);
        entity.setFirstName("First");
        entity.setLastName("Last");
        entity.setProfilePicUrl("http://pic.url");
        entity.setJoinedDate(now);

        // When
        BusinessUser user = mapper.toBusinessUser(entity);

        // Then
        assertNotNull(user);
        assertEquals(id, user.id());
        assertEquals("testuser", user.userName());
        assertEquals(1234, user.hashtag());
        assertEquals("First", user.firstName());
        assertEquals("Last", user.lastName());
        assertEquals("http://pic.url", user.profilePicUrl());
        assertEquals(now, user.joinedDate());
    }

    @Test
    void toUserEntity_ShouldMapAllFields() {
        // Given
        UUID id = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();
        BusinessUser user = new BusinessUser(
                id,
                "testuser",
                1234,
                "First",
                "Last",
                "http://pic.url",
                now);

        // When
        UserEntity entity = mapper.toUserEntity(user);

        // Then
        assertNotNull(entity);
        assertEquals(id, entity.getId());
        assertEquals("testuser", entity.getUserName());
        assertEquals(1234, entity.getHashtag());
        assertEquals("First", entity.getFirstName());
        assertEquals("Last", entity.getLastName());
        assertEquals("http://pic.url", entity.getProfilePicUrl());
        assertEquals(now, entity.getJoinedDate());
    }

    @Test
    void nullMappings_ShouldReturnNull() {
        assertNull(mapper.toBusinessUser(null));
        assertNull(mapper.toUserEntity(null));
    }
}
