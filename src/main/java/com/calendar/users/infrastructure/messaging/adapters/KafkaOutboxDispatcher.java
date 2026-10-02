package com.calendar.users.infrastructure.messaging.adapters;

import com.calendar.users.exception.TechnicalErrorCode;
import com.calendar.users.exception.TechnicalException;
import com.calendar.users.infrastructure.messaging.models.OutboxEventType;
import com.calendar.users.infrastructure.persistence.models.entities.OutboxEventRow;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.ObjectMapper;

/**
 * Sends one outbox row to Kafka.
 *
 * <p>Replaces {@code KafkaUserEventPublisherAdapter}, which implemented the domain's
 * {@code UserEventPublisher} and ran on the request path. Publishing is no longer part of
 * provisioning, so the class that does it is no longer an adapter for a domain port: it is
 * a detail of the relay, and takes a stored row rather than a {@code BusinessUser}.
 *
 * <p>The row's {@code type} resolves both the binding to send on and the class the payload
 * is read back into — see {@link OutboxEventType} for why the relay deserialises rather
 * than forwarding the stored bytes.
 */
@Component
public class KafkaOutboxDispatcher {

    private final ObjectMapper objectMapper;
    private final StreamBridge streamBridge;

    public KafkaOutboxDispatcher(ObjectMapper objectMapper, StreamBridge streamBridge) {
        this.objectMapper = objectMapper;
        this.streamBridge = streamBridge;
    }

    /**
     * Completes when the event has been handed to the broker, errors when it has not.
     *
     * <p>Errors are the relay's signal to stop the batch and count an attempt against this
     * row, so everything that can go wrong — an unknown type, an unreadable payload, a
     * broker that refuses the message — has to arrive as an error signal rather than as a
     * thrown exception or a silent {@code false}.
     */
    public Mono<Void> dispatch(OutboxEventRow event) {
        return Mono.fromCallable(() -> {
                    OutboxEventType type = OutboxEventType.from(event.type());
                    Object payload = objectMapper.readValue(event.payload(), type.payloadClass());
                    return streamBridge.send(type.destination(), payload);
                })
                // StreamBridge.send is synchronous: it can block while the producer fetches
                // topic metadata, and it waits for the acks the binder is configured to
                // require. Running that on an event loop thread stalls every other request
                // the loop is serving.
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(sent -> Boolean.TRUE.equals(sent)
                        ? Mono.<Void>empty()
                        : Mono.<Void>error(new TechnicalException(TechnicalErrorCode.KAFKA_ERROR)));
    }
}
