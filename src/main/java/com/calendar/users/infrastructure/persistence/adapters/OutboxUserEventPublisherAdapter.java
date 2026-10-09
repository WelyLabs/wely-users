package com.calendar.users.infrastructure.persistence.adapters;

import com.calendar.users.domain.models.BusinessUser;
import com.calendar.users.domain.ports.UserEventPublisher;
import com.calendar.users.infrastructure.messaging.mappers.KafkaDataMapper;
import com.calendar.users.infrastructure.messaging.models.OutboxEventType;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

/**
 * Implements the domain's {@link UserEventPublisher} by writing the event to the outbox table,
 * inside the transaction that saves the user. {@code OutboxRelay} sends it to Kafka later.
 */
@Component
public class OutboxUserEventPublisherAdapter implements UserEventPublisher {

    private final KafkaDataMapper kafkaDataMapper;
    private final ObjectMapper objectMapper;
    private final R2dbcOutboxEventStoreAdapter outboxEventStore;

    public OutboxUserEventPublisherAdapter(KafkaDataMapper kafkaDataMapper,
                                           ObjectMapper objectMapper,
                                           R2dbcOutboxEventStoreAdapter outboxEventStore) {
        this.kafkaDataMapper = kafkaDataMapper;
        this.objectMapper = objectMapper;
        this.outboxEventStore = outboxEventStore;
    }

    @Override
    public Mono<UUID> publishUserCreatedEvent(BusinessUser businessUser) {
        return Mono.fromCallable(() ->
                        objectMapper.writeValueAsString(kafkaDataMapper.toUserCreatedEventDTO(businessUser)))
                .flatMap(payload -> outboxEventStore.append(
                        businessUser.id(),
                        OutboxEventType.USER_CREATED.name(),
                        payload))
                .thenReturn(businessUser.id());
    }
}
