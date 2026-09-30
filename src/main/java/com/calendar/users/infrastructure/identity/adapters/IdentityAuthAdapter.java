package com.calendar.users.infrastructure.identity.adapters;

import com.calendar.users.domain.ports.IdentityProvider;
import com.calendar.users.infrastructure.identity.api.KeycloakAdminApi;
import com.calendar.users.infrastructure.identity.models.KeycloakUserResponse;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Reads users from Keycloak's Admin API.
 *
 * <p>Failures are logged and mapped to {@code TechnicalException(KEYCLOAK_ERROR)} by
 * {@link com.calendar.users.infrastructure.observability.InfrastructureErrorAspect}, under
 * its own error code: an unreachable Keycloak is a different incident from a database
 * timeout and should not surface as one.
 */
@Component
public class IdentityAuthAdapter implements IdentityProvider {

    private final KeycloakAdminApi keycloakAdminApi;

    public IdentityAuthAdapter(KeycloakAdminApi keycloakAdminApi) {
        this.keycloakAdminApi = keycloakAdminApi;
    }

    @Override
    public Mono<KeycloakUserResponse> getUser(String keycloakId) {
        return keycloakAdminApi.getUser(keycloakId);
    }
}
