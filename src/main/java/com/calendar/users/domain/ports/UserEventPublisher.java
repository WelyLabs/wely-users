package com.calendar.users.domain.ports;

import com.calendar.users.domain.models.BusinessUser;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface UserEventPublisher {

    Mono<UUID> publishUserCreatedEvent(BusinessUser businessUser);
}
