package com.calendar.users.infrastructure.persistence.repositories;

import com.calendar.users.infrastructure.persistence.models.entities.OutboxEventEntity;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.Collection;

/** Spring Data access to {@code outbox_event}. Inserts use the inherited {@code save}. */
@Repository
public interface OutboxEventR2dbcRepository extends ReactiveCrudRepository<OutboxEventEntity, Long> {

    /**
     * Locks the next unpublished events. SKIP LOCKED lets two relays take different rows
     * instead of both sending the same ones.
     */
    @Query("""
            SELECT *
              FROM outbox_event
             WHERE published_at IS NULL
               AND attempts < :maxAttempts
             ORDER BY id
             LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """)
    Flux<OutboxEventEntity> claimPending(int batchSize, int maxAttempts);

    @Modifying
    @Query("UPDATE outbox_event SET published_at = now() WHERE id IN (:ids)")
    Mono<Long> markPublished(Collection<Long> ids);

    @Modifying
    @Query("UPDATE outbox_event SET attempts = attempts + 1, last_error = :error WHERE id = :id")
    Mono<Long> recordFailure(long id, String error);

    @Modifying
    @Query("DELETE FROM outbox_event WHERE published_at IS NOT NULL AND published_at < :cutoff")
    Mono<Long> deletePublishedBefore(OffsetDateTime cutoff);
}
