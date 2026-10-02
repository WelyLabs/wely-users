package com.calendar.users.infrastructure.persistence.adapters;

import com.calendar.users.infrastructure.persistence.models.entities.OutboxEventRow;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * Every statement against {@code outbox_event}.
 *
 * <p>Hand-written SQL through {@link DatabaseClient} rather than a Spring Data repository:
 * the one query that matters here, the claim, needs {@code FOR UPDATE SKIP LOCKED}, and no
 * derived method name produces that.
 *
 * <p>In {@code persistence.adapters} on purpose. The package is what
 * {@code InfrastructureErrorAspect} keys on, so a failure in any of these methods is logged
 * once and surfaces as {@code USR-TEC-002}, "database unavailable" — which is exactly what
 * it is, including when it happens on the relay's side rather than on a request.
 */
@Component
public class R2dbcOutboxEventStoreAdapter {

    private static final int MAX_STORED_ERROR_LENGTH = 1000;

    private final DatabaseClient databaseClient;

    public R2dbcOutboxEventStoreAdapter(DatabaseClient databaseClient) {
        this.databaseClient = databaseClient;
    }

    /**
     * Appends one event.
     *
     * <p>No transaction of its own. The caller provisioning a user has already opened one,
     * and this statement joins it through the subscriber context — which is the entire
     * point of the pattern: the row and the user commit together.
     */
    public Mono<Long> append(UUID aggregateId, String type, String payload) {
        return databaseClient.sql("""
                        INSERT INTO outbox_event (aggregate_id, type, payload)
                        VALUES (:aggregateId, :type, :payload)
                        RETURNING id
                        """)
                .bind("aggregateId", aggregateId)
                .bind("type", type)
                .bind("payload", payload)
                .map(row -> row.get("id", Long.class))
                .one();
    }

    /**
     * Takes the next batch of unpublished events and locks them for this transaction.
     *
     * <h2>FOR UPDATE SKIP LOCKED</h2>
     *
     * <p>{@code FOR UPDATE} locks the rows this statement returns; {@code SKIP LOCKED} says
     * to pass over rows another transaction already holds instead of waiting for them. Two
     * relay instances therefore take disjoint batches and neither blocks. Without it they
     * would both read the same rows and publish everything twice.
     *
     * <p>The service runs at one replica today, so this changes nothing yet. It is written
     * now because the day the replica count goes to two is a one-line edit to a manifest,
     * and the duplicate publishing it would otherwise cause announces itself nowhere.
     *
     * <h2>Why the attempts cap is in the WHERE clause</h2>
     *
     * <p>Rows are read in id order, so an event that can never be sent would be picked up
     * first on every pass and block everything behind it. Past {@code maxAttempts} it stops
     * being selected and stays in the table with its {@code last_error}, where it can be
     * found and dealt with.
     */
    public Flux<OutboxEventRow> claimPending(int batchSize, int maxAttempts) {
        return databaseClient.sql("""
                        SELECT id, aggregate_id, type, payload, attempts
                          FROM outbox_event
                         WHERE published_at IS NULL
                           AND attempts < :maxAttempts
                         ORDER BY id
                         LIMIT :batchSize
                        FOR UPDATE SKIP LOCKED
                        """)
                .bind("maxAttempts", maxAttempts)
                .bind("batchSize", batchSize)
                .map(row -> new OutboxEventRow(
                        row.get("id", Long.class),
                        row.get("aggregate_id", UUID.class),
                        row.get("type", String.class),
                        row.get("payload", String.class),
                        row.get("attempts", Integer.class)))
                .all();
    }

    /**
     * Marks events as published.
     *
     * <p>Runs in the same transaction as the claim, so the locks are released by the same
     * commit that records the outcome. The gap this cannot close is the one between the
     * send and this statement: a process that dies there leaves the rows unpublished and
     * they go out again. That is what makes the delivery at-least-once, and why the
     * consumer has to be idempotent.
     */
    public Mono<Long> markPublished(List<Long> ids) {
        if (ids.isEmpty()) {
            return Mono.just(0L);
        }

        return databaseClient.sql("""
                        UPDATE outbox_event
                           SET published_at = now()
                         WHERE id = ANY(:ids)
                        """)
                .bind("ids", ids.toArray(new Long[0]))
                .fetch()
                .rowsUpdated();
    }

    /**
     * Counts one failed delivery attempt and keeps the reason.
     *
     * <p>Deliberately outside the claim transaction: that one rolls back when a send fails,
     * which would take the increment with it and leave the relay retrying the same row
     * forever with {@code attempts} stuck at zero.
     */
    public Mono<Long> recordFailure(long id, String error) {
        return databaseClient.sql("""
                        UPDATE outbox_event
                           SET attempts = attempts + 1,
                               last_error = :error
                         WHERE id = :id
                        """)
                .bind("error", truncate(error))
                .bind("id", id)
                .fetch()
                .rowsUpdated();
    }

    /**
     * Deletes published events older than the cutoff.
     *
     * <p>A published row is only evidence. Keeping it costs disk and lengthens every index
     * scan; deleting it immediately removes the one trace that answers "did this event
     * actually go out, and when" during an incident. The retention window is that trade-off
     * made explicit.
     */
    public Mono<Long> purgePublishedBefore(Instant cutoff) {
        return databaseClient.sql("""
                        DELETE FROM outbox_event
                         WHERE published_at IS NOT NULL
                           AND published_at < :cutoff
                        """)
                .bind("cutoff", OffsetDateTime.ofInstant(cutoff, ZoneOffset.UTC))
                .fetch()
                .rowsUpdated();
    }

    /**
     * Keeps {@code last_error} readable. A driver stack trace rendered into a message can
     * run to kilobytes, and the table would then carry more failure text than payload.
     */
    private String truncate(String error) {
        if (error == null) {
            return null;
        }
        return error.length() <= MAX_STORED_ERROR_LENGTH
                ? error
                : error.substring(0, MAX_STORED_ERROR_LENGTH);
    }
}
