package com.calendar.users.domain.ports;

import com.calendar.users.infrastructure.models.dtos.KeycloakUserResponse;
import reactor.core.publisher.Mono;

public interface KeycloakPort {

    Mono<KeycloakUserResponse> getUser(String keycloakId);
}
