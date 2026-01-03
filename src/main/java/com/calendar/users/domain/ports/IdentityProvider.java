package com.calendar.users.domain.ports;

import com.calendar.users.infrastructure.identity.models.KeycloakUserResponse;
import reactor.core.publisher.Mono;

public interface IdentityProvider {

    Mono<KeycloakUserResponse> getUser(String keycloakId);
}
