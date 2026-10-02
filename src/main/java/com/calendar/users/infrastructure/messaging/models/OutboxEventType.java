package com.calendar.users.infrastructure.messaging.models;

import java.util.Arrays;

/**
 * The event kinds the outbox can carry, and where each one goes.
 *
 * <p>An outbox row stores its payload as text, so something has to say what those bytes
 * mean. This enum is that registry: a {@code type} column value maps to the binding the
 * relay sends on and to the class the payload is read back into.
 *
 * <h2>Why the relay deserialises at all</h2>
 *
 * <p>Sending the stored JSON straight through would save a round trip. It would also
 * break partitioning: the binding resolves the Kafka key with
 * {@code partition-key-expression=payload.userId}, a SpEL expression over the message
 * payload, which only works on an object with that property. Reading the row back into
 * its DTO keeps the bytes on the wire identical to what the service produced before the
 * outbox existed, so consumers see no change at all.
 */
public enum OutboxEventType {

    USER_CREATED("userCreated-out-0", UserCreatedEventDTO.class);

    private final String destination;
    private final Class<?> payloadClass;

    OutboxEventType(String destination, Class<?> payloadClass) {
        this.destination = destination;
        this.payloadClass = payloadClass;
    }

    public String destination() {
        return destination;
    }

    public Class<?> payloadClass() {
        return payloadClass;
    }

    /**
     * Resolves a stored {@code type} value.
     *
     * <p>Throws rather than skipping: a row naming a type this build does not know is
     * either a rollback to an older image or a bug, and both deserve to be visible. The
     * relay turns the failure into an attempt count, so after a few passes the row stops
     * blocking the queue and waits in the table instead.
     */
    public static OutboxEventType from(String name) {
        return Arrays.stream(values())
                .filter(type -> type.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown outbox event type: " + name));
    }
}
