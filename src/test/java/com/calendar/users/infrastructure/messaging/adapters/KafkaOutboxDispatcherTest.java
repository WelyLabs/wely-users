package com.calendar.users.infrastructure.messaging.adapters;

import com.calendar.users.exception.TechnicalErrorCode;
import com.calendar.users.exception.TechnicalException;
import com.calendar.users.infrastructure.messaging.models.UserCreatedEventDTO;
import com.calendar.users.infrastructure.persistence.models.entities.OutboxEventEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.stream.function.StreamBridge;
import reactor.test.StepVerifier;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KafkaOutboxDispatcherTest {

    @Mock
    private StreamBridge streamBridge;

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    private KafkaOutboxDispatcher dispatcher() {
        return new KafkaOutboxDispatcher(objectMapper, streamBridge);
    }

    private static OutboxEventEntity aRow(UUID userId, String type, String payload) {
        return OutboxEventEntity.builder()
                .id(1L)
                .aggregateId(userId)
                .type(type)
                .payload(payload)
                .attempts(0)
                .build();
    }

    private static String aPayload(UUID userId) {
        return """
                {"userId":"%s","userName":"alice","hashtag":1111,"profilePicUrl":"pic"}"""
                .formatted(userId);
    }

    @Test
    @DisplayName("the stored payload is sent as the DTO, on the binding the type names")
    void dispatch_shouldSendTheDeserialisedPayload() {
        // Sending the raw JSON text would be cheaper and would break partitioning: the
        // binding resolves the Kafka key with the SpEL expression payload.userId, which
        // needs an object with that property, not a string.
        UUID userId = UUID.randomUUID();
        when(streamBridge.send(anyString(), any())).thenReturn(true);

        StepVerifier.create(dispatcher().dispatch(aRow(userId, "USER_CREATED", aPayload(userId))))
                .verifyComplete();

        ArgumentCaptor<Object> sent = ArgumentCaptor.captor();
        verify(streamBridge).send(eq("userCreated-out-0"), sent.capture());
        assertThat(sent.getValue()).isEqualTo(new UserCreatedEventDTO(userId, "alice", 1111, "pic"));
    }

    @Test
    @DisplayName("a refused send is an error, not a silent false")
    void dispatch_shouldErrorWhenStreamBridgeRefuses() {
        // StreamBridge reports failure by returning false. Letting that through as a
        // completion would mark the row published without anything having been sent.
        UUID userId = UUID.randomUUID();
        when(streamBridge.send(anyString(), any())).thenReturn(false);

        StepVerifier.create(dispatcher().dispatch(aRow(userId, "USER_CREATED", aPayload(userId))))
                .expectErrorMatches(error -> error instanceof TechnicalException technical
                        && technical.getErrorCode() == TechnicalErrorCode.KAFKA_ERROR)
                .verify();
    }

    @Test
    @DisplayName("an unknown type errors instead of guessing a destination")
    void dispatch_shouldErrorOnAnUnknownType() {
        // A row naming a type this build does not know is a rollback to an older image or
        // a bug. Either way, sending it somewhere plausible would be worse than failing.
        StepVerifier.create(dispatcher().dispatch(aRow(UUID.randomUUID(), "USER_RENAMED", "{}")))
                .expectError(IllegalArgumentException.class)
                .verify();

        verify(streamBridge, never()).send(anyString(), any());
    }

    @Test
    @DisplayName("an unreadable payload errors as a signal, not as a thrown exception")
    void dispatch_shouldErrorOnAnUnreadablePayload() {
        // The relay reacts to error signals; something thrown during assembly would escape
        // it and take the loop down with it.
        StepVerifier.create(dispatcher().dispatch(aRow(UUID.randomUUID(), "USER_CREATED", "not json")))
                .expectError()
                .verify();

        verify(streamBridge, never()).send(anyString(), any());
    }

    @Test
    @DisplayName("nothing is sent until the dispatch is subscribed")
    void dispatch_shouldBeLazy() {
        dispatcher().dispatch(aRow(UUID.randomUUID(), "USER_CREATED", "{}"));

        verify(streamBridge, never()).send(anyString(), any());
    }
}
