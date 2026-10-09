package com.calendar.users.infrastructure.persistence.adapters;

import com.calendar.users.domain.models.BusinessUser;
import com.calendar.users.infrastructure.messaging.mappers.KafkaDataMapper;
import com.calendar.users.infrastructure.messaging.models.OutboxEventType;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

/**
 * Writes the USER_CREATED event of a new user to the outbox table.
 * Called inside the transaction that saves the user; {@code OutboxRelay} sends the event to Kafka.
 */
@Component
public class UserCreatedOutboxWriter {

    private final KafkaDataMapper kafkaDataMapper;
    private final ObjectMapper objectMapper;
    private final R2dbcOutboxEventStoreAdapter outboxEventStore;

    public UserCreatedOutboxWriter(KafkaDataMapper kafkaDataMapper,
                                   ObjectMapper objectMapper,
                                   R2dbcOutboxEventStoreAdapter outboxEventStore) {
        this.kafkaDataMapper = kafkaDataMapper;
        this.objectMapper = objectMapper;
        this.outboxEventStore = outboxEventStore;
    }

    /** Returns the id of the outbox row. */
    public Mono<Long> write(BusinessUser user) {
        return Mono.fromCallable(() -> objectMapper.writeValueAsString(kafkaDataMapper.toUserCreatedEventDTO(user)))
                .flatMap(payload -> outboxEventStore.append(user.id(), OutboxEventType.USER_CREATED.name(), payload));
    }
}
