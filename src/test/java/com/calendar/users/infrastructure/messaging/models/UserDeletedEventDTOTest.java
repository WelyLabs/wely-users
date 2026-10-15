package com.calendar.users.infrastructure.messaging.models;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.function.json.JsonMapper;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Pins USER_DELETED's wire format, which four other services read: deletedAt is an ISO-8601 string. */
@SpringBootTest
@ActiveProfiles("test")
class UserDeletedEventDTOTest {

    private static final UUID USER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UserDeletedEventDTO EVENT =
            new UserDeletedEventDTO(USER, Instant.parse("2026-10-15T14:03:27.123456Z"));
    private static final String WIRE = "{\"userId\":\"" + USER + "\",\"deletedAt\":\"2026-10-15T14:03:27.123456Z\"}";

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JsonMapper streamJsonMapper;

    @Test
    @DisplayName("the outbox payload, written by the application ObjectMapper")
    void writeValueAsString_shouldWriteDeletedAtAsAnIsoInstant_withTheApplicationObjectMapper() {
        assertThat(objectMapper.writeValueAsString(EVENT)).isEqualTo(WIRE);
    }

    @Test
    @DisplayName("the Kafka record, written by Spring Cloud Function's JsonMapper")
    void toJson_shouldWriteDeletedAtAsAnIsoInstant_withTheStreamJsonMapperBean() {
        assertThat(new String(streamJsonMapper.toJson(EVENT), StandardCharsets.UTF_8)).isEqualTo(WIRE);
    }

    @Test
    @DisplayName("a row written before deletedAt existed is still read and sent")
    void readValue_shouldAcceptAnOlderPayload_whenDeletedAtIsAbsent() {
        assertThat(objectMapper.readValue("{\"userId\":\"" + USER + "\"}", UserDeletedEventDTO.class))
                .isEqualTo(new UserDeletedEventDTO(USER, null));
    }
}
