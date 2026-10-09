package com.calendar.users.infrastructure.messaging.models;

import java.util.Arrays;

/**
 * Event types the outbox can carry: for each, the Kafka binding and the payload class.
 * The relay reads the payload back into its DTO because the partition key is computed from it.
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

        /** Throws on an unknown type, so the relay counts it as a failed attempt. */
    public static OutboxEventType from(String name) {
        return Arrays.stream(values())
                .filter(type -> type.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown outbox event type: " + name));
    }
}
