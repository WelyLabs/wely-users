package com.calendar.users.infrastructure.persistence.adapters;

import com.calendar.users.infrastructure.persistence.models.entities.OutboxEventEntity;
import com.calendar.users.infrastructure.persistence.repositories.OutboxEventR2dbcRepository;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/** Reads and writes the {@code outbox_event} table, through {@link OutboxEventR2dbcRepository}. */
@Component
public class R2dbcOutboxEventStoreAdapter {

    private static final int MAX_STORED_ERROR_LENGTH = 1000;

    private final OutboxEventR2dbcRepository outboxEventRepository;

    public R2dbcOutboxEventStoreAdapter(OutboxEventR2dbcRepository outboxEventRepository) {
        this.outboxEventRepository = outboxEventRepository;
    }

    /** Inserts an event and returns its id. Joins the caller's transaction, the one saving the user. */
    public Mono<Long> append(UUID aggregateId, String type, String payload) {
        OutboxEventEntity event = OutboxEventEntity.builder()
                .aggregateId(aggregateId)
                .type(type)
                .payload(payload)
                .build();
        return outboxEventRepository.save(event).map(OutboxEventEntity::getId);
    }

    /** Locks and returns the next unpublished events, for the relay. */
    public Flux<OutboxEventEntity> claimPending(int batchSize, int maxAttempts) {
        return outboxEventRepository.claimPending(batchSize, maxAttempts);
    }

    public Mono<Long> markPublished(List<Long> ids) {
        if (ids.isEmpty()) {
            return Mono.just(0L);
        }
        return outboxEventRepository.markPublished(ids);
    }

    public Mono<Long> recordFailure(long id, String error) {
        return outboxEventRepository.recordFailure(id, truncate(error));
    }

    public Mono<Long> purgePublishedBefore(Instant cutoff) {
        return outboxEventRepository.deletePublishedBefore(OffsetDateTime.ofInstant(cutoff, ZoneOffset.UTC));
    }

    private String truncate(String error) {
        if (error == null) {
            return null;
        }
        return error.length() <= MAX_STORED_ERROR_LENGTH
                ? error
                : error.substring(0, MAX_STORED_ERROR_LENGTH);
    }
}
