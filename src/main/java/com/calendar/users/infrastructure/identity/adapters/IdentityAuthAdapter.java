package com.calendar.users.infrastructure.identity.adapters;

import com.calendar.users.domain.ports.IdentityProvider;
import com.calendar.users.exception.TechnicalErrorCode;
import com.calendar.users.exception.TechnicalException;
import com.calendar.users.infrastructure.identity.api.KeycloakAdminApi;
import com.calendar.users.infrastructure.identity.models.KeycloakUserResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Slf4j
@Component
public class IdentityAuthAdapter implements IdentityProvider {

    private final KeycloakAdminApi keycloakAdminApi;

    public IdentityAuthAdapter(KeycloakAdminApi keycloakAdminApi) {
        this.keycloakAdminApi = keycloakAdminApi;
    }

    public Mono<KeycloakUserResponse> getUser(String keycloakId) {
        return keycloakAdminApi.getUser(keycloakId)
                .onErrorMap(e -> {
                    log.error("Erreur Keycloak : {}", e.getMessage());
                    return new TechnicalException(TechnicalErrorCode.KEYCLOAK_ERROR);
                });
    }
}
