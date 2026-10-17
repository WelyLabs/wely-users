package com.calendar.users.infrastructure.messaging.mappers;

import java.time.LocalDate;

import com.calendar.users.infrastructure.messaging.models.UserBirthdayEventDTO;

import com.calendar.users.domain.models.BusinessUser;
import com.calendar.users.infrastructure.messaging.models.UserCreatedEventDTO;
import com.calendar.users.infrastructure.messaging.models.UserModifiedEventDTO;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class KafkaDataMapperTest {

    private final KafkaDataMapper mapper = Mappers.getMapper(KafkaDataMapper.class);

    @Test
    void toUserCreatedEventDTO_ShouldMapAllFields() {
        // Given
        UUID id = UUID.randomUUID();
        BusinessUser user = new BusinessUser(
                id,
                "testuser",
                1234,
                "First",
                "Last",
                "http://pic.url",
                LocalDateTime.now());

        // When
        UserCreatedEventDTO dto = mapper.toUserCreatedEventDTO(user);

        // Then
        assertNotNull(dto);
        assertEquals(id, dto.userId());
        assertEquals("testuser", dto.userName());
        assertEquals(1234, dto.hashtag());
        assertEquals("http://pic.url", dto.profilePicUrl());
    }

    @Test
    void toUserCreatedEventDTO_WithNull_ShouldReturnNull() {
        assertNull(mapper.toUserCreatedEventDTO(null));
    }

    @Test
    void toUserModifiedEventDTO_ShouldCarryTheWholeProfile() {
        UUID id = UUID.randomUUID();
        BusinessUser user = new BusinessUser(id, "camille_d", 4321, "Cam", "Dupont", "pic.jpg", LocalDateTime.now());

        assertEquals(new UserModifiedEventDTO(id, "camille_d", 4321, "Cam", "Dupont", "pic.jpg"),
                mapper.toUserModifiedEventDTO(user));
    }

    @Test
    void toUserBirthdayEventDTO_shouldCarryTheFirstNameAndTheDayAndMonth_whenTheFirstNameIsSet() {
        UUID id = UUID.randomUUID();
        BusinessUser user = new BusinessUser(id, "lea", 4242, "Léa", "Martin", null, LocalDateTime.now(),
                LocalDate.of(1995, 3, 12), true);

        assertEquals(new UserBirthdayEventDTO(id, "Léa", 3, 12), mapper.toUserBirthdayEventDTO(user));
    }

    @Test
    void toUserBirthdayEventDTO_shouldFallBackOnTheUserName_whenNoFirstNameIsSet() {
        UUID id = UUID.randomUUID();
        BusinessUser user = new BusinessUser(id, "lea", 4242, null, null, null, LocalDateTime.now(),
                LocalDate.of(2000, 2, 29), true);

        assertEquals(new UserBirthdayEventDTO(id, "lea", 2, 29), mapper.toUserBirthdayEventDTO(user));
    }

    @Test
    void toUserBirthdayEventDTO_shouldReturnNull_whenGivenNull() {
        assertNull(mapper.toUserBirthdayEventDTO(null));
    }
}
