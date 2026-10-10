package com.calendar.users.infrastructure.persistence.models.entities;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One row of {@code outbox_event}: an event to send to Kafka.
 * Leave {@code createdAt} and {@code attempts} null when inserting: the database defaults apply.
 */
@Setter
@Getter
@Builder
@Table("outbox_event")
@AllArgsConstructor
@NoArgsConstructor
public class OutboxEventEntity {

    @Id
    private Long id;

    /** The user the event is about; also the Kafka partition key. */
    @Column("aggregate_id")
    private UUID aggregateId;

    /** One of {@code OutboxEventType}. */
    @Column("type")
    private String type;

    /** The event, as JSON. */
    @Column("payload")
    private String payload;

    @Column("created_at")
    private OffsetDateTime createdAt;

    /** Null until the relay has sent the event. */
    @Column("published_at")
    private OffsetDateTime publishedAt;

    @Column("attempts")
    private Integer attempts;

    @Column("last_error")
    private String lastError;
}
