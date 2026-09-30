package com.calendar.users.infrastructure.identity.adapters;

import com.calendar.users.domain.models.IdentityUser;
import com.calendar.users.domain.ports.IdentityProvider;
import com.calendar.users.infrastructure.identity.api.KeycloakAdminApi;
import com.calendar.users.infrastructure.identity.mappers.IdentityUserMapper;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Reads users from Keycloak's Admin API and hands the domain its own model.
 *
 * <p>This class is where the vendor stops: {@code KeycloakUserResponse} does not travel
 * past it. Failures are logged and mapped to {@code TechnicalException(KEYCLOAK_ERROR)} by
 * {@link com.calendar.users.infrastructure.observability.InfrastructureErrorAspect}, under
 * its own error code — an unreachable Keycloak is a different incident from a database
 * timeout and should not surface as one.
 */
@Component
public class IdentityAuthAdapter implements IdentityProvider {

    private final KeycloakAdminApi keycloakAdminApi;
    private final IdentityUserMapper identityUserMapper;

    public IdentityAuthAdapter(KeycloakAdminApi keycloakAdminApi,
                               IdentityUserMapper identityUserMapper) {
        this.keycloakAdminApi = keycloakAdminApi;
        this.identityUserMapper = identityUserMapper;
    }

    @Override
    public Mono<IdentityUser> getUser(String subjectId) {
        return keycloakAdminApi.getUser(subjectId).map(identityUserMapper::toIdentityUser);
    }
}
