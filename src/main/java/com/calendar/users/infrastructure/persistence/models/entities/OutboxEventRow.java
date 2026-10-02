package com.calendar.users.infrastructure.persistence.models.entities;

import java.util.UUID;

/**
 * One row of {@code outbox_event}, as the relay needs it.
 *
 * <p>A record rather than a Lombok entity, and not a Spring Data one: every statement
 * against this table is written by hand in
 * {@code R2dbcOutboxEventStoreAdapter}, because {@code FOR UPDATE SKIP LOCKED} is not
 * something a derived query method can express.
 *
 * <p>It carries only what dispatching needs. {@code created_at}, {@code published_at}
 * and {@code last_error} exist in the table for diagnosis and retention, and are read
 * with psql, not with this type.
 */
public record OutboxEventRow(
        long id,
        UUID aggregateId,
        String type,
        String payload,
        int attempts
) {}
