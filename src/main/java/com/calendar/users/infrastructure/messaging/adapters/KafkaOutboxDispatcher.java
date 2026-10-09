package com.calendar.users.infrastructure.messaging.adapters;

import com.calendar.users.exception.TechnicalErrorCode;
import com.calendar.users.exception.TechnicalException;
import com.calendar.users.infrastructure.messaging.models.OutboxEventType;
import com.calendar.users.infrastructure.persistence.models.entities.OutboxEventEntity;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.ObjectMapper;

/** Sends one outbox event to Kafka, on the binding its type points to. */
@Component
public class KafkaOutboxDispatcher {

    private final ObjectMapper objectMapper;
    private final StreamBridge streamBridge;

    public KafkaOutboxDispatcher(ObjectMapper objectMapper, StreamBridge streamBridge) {
        this.objectMapper = objectMapper;
        this.streamBridge = streamBridge;
    }

        /** Completes once Kafka has the event; any problem arrives as an error signal. */
    public Mono<Void> dispatch(OutboxEventEntity event) {
        return Mono.fromCallable(() -> {
                    OutboxEventType type = OutboxEventType.from(event.getType());
                    Object payload = objectMapper.readValue(event.getPayload(), type.payloadClass());
                    return streamBridge.send(type.destination(), payload);
                })
                // StreamBridge.send blocks: keep it off the event loop threads.
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(sent -> Boolean.TRUE.equals(sent)
                        ? Mono.<Void>empty()
                        : Mono.<Void>error(new TechnicalException(TechnicalErrorCode.KAFKA_ERROR)));
    }
}
