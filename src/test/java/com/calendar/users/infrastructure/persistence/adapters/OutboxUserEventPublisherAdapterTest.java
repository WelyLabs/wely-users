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
class OutboxUserEventPublisherAdapterTest {

    @Mock
    private KafkaDataMapper kafkaDataMapper;

    @Mock
    private R2dbcOutboxEventStoreAdapter outboxEventStore;

    // A real mapper, not a mock. The bytes this adapter stores are the bytes the relay
    // later puts on the wire, so what they look like is the contract — and a mocked
    // serialiser would assert nothing about it.
    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    private OutboxUserEventPublisherAdapter adapter() {
        return new OutboxUserEventPublisherAdapter(kafkaDataMapper, objectMapper, outboxEventStore);
    }

    private static BusinessUser aUser(UUID id) {
        return new BusinessUser(id, "alice", 1111, "First", "Last", "pic", LocalDateTime.now());
    }

    @Test
    @DisplayName("the event is appended to the outbox, keyed on the user, and the id comes back")
    void publishUserCreatedEvent_shouldAppendTheEvent() {
        UUID userId = UUID.randomUUID();
        BusinessUser user = aUser(userId);
        when(kafkaDataMapper.toUserCreatedEventDTO(user))
                .thenReturn(new UserCreatedEventDTO(userId, "alice", 1111, "pic"));
        when(outboxEventStore.append(any(UUID.class), anyString(), anyString())).thenReturn(Mono.just(42L));

        StepVerifier.create(adapter().publishUserCreatedEvent(user))
                .expectNext(userId)
                .verifyComplete();

        ArgumentCaptor<String> payload = ArgumentCaptor.captor();
        // aggregate_id is the user id, because it becomes the Kafka partition key: that is
        // what keeps several events about one user ordered relative to each other.
        verify(outboxEventStore).append(eq(userId), eq(OutboxEventType.USER_CREATED.name()), payload.capture());

        UserCreatedEventDTO roundTripped =
                objectMapper.readValue(payload.getValue(), UserCreatedEventDTO.class);
        assertThat(roundTripped).isEqualTo(new UserCreatedEventDTO(userId, "alice", 1111, "pic"));
    }

    @Test
    @DisplayName("a failing insert fails the publish, so the whole provisioning rolls back")
    void publishUserCreatedEvent_shouldPropagateAStoreFailure() {
        // Swallowing this would be the bug the outbox exists to prevent, in a new place: the
        // user would be committed with no event recorded alongside it.
        UUID userId = UUID.randomUUID();
        BusinessUser user = aUser(userId);
        when(kafkaDataMapper.toUserCreatedEventDTO(user))
                .thenReturn(new UserCreatedEventDTO(userId, "alice", 1111, "pic"));
        when(outboxEventStore.append(any(UUID.class), anyString(), anyString()))
                .thenReturn(Mono.error(new IllegalStateException("connection reset by peer")));

        StepVerifier.create(adapter().publishUserCreatedEvent(user))
                .expectError(IllegalStateException.class)
                .verify();
    }

    @Test
    @DisplayName("nothing is written until the publisher is subscribed")
    void publishUserCreatedEvent_shouldBeLazy() {
        // The adapter is called while the unit of work is being assembled, before the
        // transaction boundary subscribes. Serialising or inserting at assembly time would
        // put the insert outside the transaction.
        BusinessUser user = aUser(UUID.randomUUID());

        adapter().publishUserCreatedEvent(user);

        verify(kafkaDataMapper, never()).toUserCreatedEventDTO(any());
        verify(outboxEventStore, never()).append(any(), anyString(), anyString());
    }
}
