package com.calendar.users.infrastructure.persistence.adapters;

import com.calendar.users.domain.models.BusinessUser;
import com.calendar.users.infrastructure.messaging.mappers.KafkaDataMapper;
import com.calendar.users.infrastructure.messaging.models.OutboxEventType;
import com.calendar.users.infrastructure.messaging.models.UserCreatedEventDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserCreatedOutboxWriterTest {

    @Mock
    private KafkaDataMapper kafkaDataMapper;

    @Mock
    private R2dbcOutboxEventStoreAdapter outboxEventStore;

    // A real mapper: the stored JSON is what the relay sends to Kafka.
    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    private UserCreatedOutboxWriter writer() {
        return new UserCreatedOutboxWriter(kafkaDataMapper, objectMapper, outboxEventStore);
    }

    private static BusinessUser aUser(UUID id) {
        return new BusinessUser(id, "alice", 1111, "First", "Last", "pic", LocalDateTime.now());
    }

    @Test
    @DisplayName("the event is appended to the outbox, keyed on the user")
    void write_shouldAppendTheEvent() {
        UUID userId = UUID.randomUUID();
        BusinessUser user = aUser(userId);
        when(kafkaDataMapper.toUserCreatedEventDTO(user))
                .thenReturn(new UserCreatedEventDTO(userId, "alice", 1111, "pic"));
        when(outboxEventStore.append(any(UUID.class), anyString(), anyString())).thenReturn(Mono.just(42L));

        StepVerifier.create(writer().write(user))
                .expectNext(42L)
                .verifyComplete();

        ArgumentCaptor<String> payload = ArgumentCaptor.captor();
        verify(outboxEventStore).append(eq(userId), eq(OutboxEventType.USER_CREATED.name()), payload.capture());

        UserCreatedEventDTO roundTripped =
                objectMapper.readValue(payload.getValue(), UserCreatedEventDTO.class);
        assertThat(roundTripped).isEqualTo(new UserCreatedEventDTO(userId, "alice", 1111, "pic"));
    }

    @Test
    @DisplayName("a failing insert fails the write, so the user is rolled back with it")
    void write_shouldPropagateAStoreFailure() {
        UUID userId = UUID.randomUUID();
        BusinessUser user = aUser(userId);
        when(kafkaDataMapper.toUserCreatedEventDTO(user))
                .thenReturn(new UserCreatedEventDTO(userId, "alice", 1111, "pic"));
        when(outboxEventStore.append(any(UUID.class), anyString(), anyString()))
                .thenReturn(Mono.error(new IllegalStateException("connection reset by peer")));

        StepVerifier.create(writer().write(user))
                .expectError(IllegalStateException.class)
                .verify();
    }

    @Test
    @DisplayName("nothing is written until the result is subscribed")
    void write_shouldBeLazy() {
        BusinessUser user = aUser(UUID.randomUUID());

        writer().write(user);

        verify(kafkaDataMapper, never()).toUserCreatedEventDTO(any());
        verify(outboxEventStore, never()).append(any(), anyString(), anyString());
    }
}
