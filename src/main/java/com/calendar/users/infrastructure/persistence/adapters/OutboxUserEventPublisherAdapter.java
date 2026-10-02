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
 * Publishes {@code USER_CREATED} by appending it to the outbox.
 *
 * <p>The domain port is unchanged: {@code UserService} still asks a
 * {@link UserEventPublisher} to publish, and does not know that the implementation stopped
 * talking to Kafka. Swapping a remote call for a local write without touching a line of
 * domain code is the whole argument for having the port in the first place.
 *
 * <h2>Why this lives in persistence and not in messaging</h2>
 *
 * <p>Because it is a database write. Nothing here reaches the broker, and when it fails it
 * fails for PostgreSQL reasons. Putting it in {@code persistence.adapters} is what makes
 * {@code InfrastructureErrorAspect} report it as {@code USR-TEC-002} rather than leaving a
 * raw driver exception to surface from the provisioning path.
 *
 * <h2>The transaction</h2>
 *
 * <p>This method opens none. It is called from inside
 * {@code TransactionBoundary.atomically}, and the R2DBC connection travels in the
 * subscriber context, so the insert joins the transaction that is saving the user. Called
 * outside one it would still work, and would be exactly the bug the outbox exists to
 * prevent — which is why {@code UserService} is where the boundary is declared, in plain
 * sight.
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

    /**
     * {@inheritDoc}
     *
     * <p>The payload is serialised with the same DTO the broker used to receive directly,
     * so the stored bytes and the published bytes are the same bytes.
     *
     * <p>{@code aggregate_id} is the user id. It becomes the Kafka partition key, which is
     * what keeps several events about one user in order relative to each other.
     */
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
