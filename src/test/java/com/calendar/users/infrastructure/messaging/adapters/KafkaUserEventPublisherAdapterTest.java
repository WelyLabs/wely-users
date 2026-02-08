package com.calendar.users.infrastructure.messaging.adapters;

import com.calendar.users.domain.models.BusinessUser;
import com.calendar.users.exception.TechnicalErrorCode;
import com.calendar.users.exception.TechnicalException;
import com.calendar.users.infrastructure.messaging.mappers.KafkaDataMapper;
import com.calendar.users.infrastructure.messaging.models.UserCreatedEventDTO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.stream.function.StreamBridge;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KafkaUserEventPublisherAdapterTest {

    @Mock
    private KafkaDataMapper kafkaDataMapper;

    @Mock
    private StreamBridge streamBridge;

    @InjectMocks
    private KafkaUserEventPublisherAdapter adapter;

    @Test
    void publishUserCreatedEvent_ShouldReturnUserId_WhenSuccess() {
        // Given
        UUID userId = UUID.randomUUID();
        BusinessUser user = new BusinessUser(userId, "user", 1, "F", "L", "url", LocalDateTime.now());
        UserCreatedEventDTO dto = new UserCreatedEventDTO(userId, "user", 1, "url");

        when(kafkaDataMapper.toUserCreatedEventDTO(user)).thenReturn(dto);
        when(streamBridge.send(eq("userCreated-out-0"), any())).thenReturn(true);

        // When
        Mono<UUID> result = adapter.publishUserCreatedEvent(user);

        // Then
        StepVerifier.create(result)
                .expectNext(userId)
                .verifyComplete();
    }

    @Test
    void publishUserCreatedEvent_ShouldError_WhenStreamBridgeReturnsFalse() {
        // Given
        UUID userId = UUID.randomUUID();
        BusinessUser user = new BusinessUser(userId, "user", 1, "F", "L", "url", LocalDateTime.now());
        UserCreatedEventDTO dto = new UserCreatedEventDTO(userId, "user", 1, "url");

        when(kafkaDataMapper.toUserCreatedEventDTO(user)).thenReturn(dto);
        when(streamBridge.send(eq("userCreated-out-0"), any())).thenReturn(false);

        // When
        Mono<UUID> result = adapter.publishUserCreatedEvent(user);

        // Then
        StepVerifier.create(result)
                .expectErrorMatches(throwable -> throwable instanceof TechnicalException &&
                        ((TechnicalException) throwable).getErrorCode() == TechnicalErrorCode.KAFKA_ERROR)
                .verify();
    }
}
