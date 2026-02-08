package com.calendar.users.infrastructure.messaging.mappers;

import com.calendar.users.domain.models.BusinessUser;
import com.calendar.users.infrastructure.messaging.models.UserCreatedEventDTO;
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
}
