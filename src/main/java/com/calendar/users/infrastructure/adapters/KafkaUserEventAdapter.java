package com.calendar.users.infrastructure.adapters;

import com.calendar.users.domain.models.BusinessUser;
import com.calendar.users.domain.ports.UserEventPublisher;
import com.calendar.users.exception.TechnicalErrorCode;
import com.calendar.users.exception.TechnicalException;
import com.calendar.users.infrastructure.mappers.KafkaDataMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Component
public class KafkaUserEventAdapter implements UserEventPublisher {

    private final KafkaDataMapper kafkaDataMapper;
    private final StreamBridge streamBridge;

    private static final String DESTINATION = "userCreated-out-0";

    public KafkaUserEventAdapter(KafkaDataMapper kafkaDataMapper, StreamBridge streamBridge) {
        this.kafkaDataMapper = kafkaDataMapper;
        this.streamBridge = streamBridge;
    }

    public Mono<Long> publishUserCreatedEvent(BusinessUser businessUser) {
        return Mono.fromCallable(() -> kafkaDataMapper.toUserCreatedEventDTO(businessUser))
                .flatMap(eventDto -> {
                    boolean sent = streamBridge.send(DESTINATION, eventDto);

                    if (!sent) {
                        return Mono.error(new TechnicalException(TechnicalErrorCode.KAFKA_ERROR));
                    }
                    return Mono.just(businessUser.id());
                });
    }
}
