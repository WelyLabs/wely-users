package com.calendar.users.infrastructure.adapters;

import com.calendar.users.domain.ports.KeycloakPort;
import com.calendar.users.infrastructure.api.KeycloakAdminApi;
import com.calendar.users.infrastructure.models.dtos.KeycloakUserResponse;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class KeycloakAdapter implements KeycloakPort {

    private final KeycloakAdminApi keycloakAdminApi;

    public KeycloakAdapter(KeycloakAdminApi keycloakAdminApi) {
        this.keycloakAdminApi = keycloakAdminApi;
    }

    public Mono<KeycloakUserResponse> getUser(String keycloakId) {
        return keycloakAdminApi.getUser(keycloakId)
                .onErrorResume(e -> Mono.error(e));
    }
}
