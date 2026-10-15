package com.calendar.users.infrastructure.identity.adapters;

import com.calendar.users.domain.models.IdentityUser;
import com.calendar.users.domain.ports.IdentityProvider;
import com.calendar.users.exception.BusinessErrorCode;
import com.calendar.users.exception.BusinessException;
import com.calendar.users.infrastructure.identity.api.KeycloakAdminApi;
import com.calendar.users.infrastructure.identity.mappers.IdentityUserMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

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

    static final Duration DELETE_TIMEOUT = Duration.ofSeconds(10);

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

    /**
     * Reads the whole representation, sets the three fields, writes it back. A partial body is not
     * safe: under Keycloak's declarative user profile, an attribute left out can be cleared.
     *
     * <p>Keycloak's refusals are the user's to fix, so they are business errors here; the aspect
     * lets those through and turns anything else into KEYCLOAK_ERROR.
     */
    @Override
    public Mono<Void> updateProfile(String subjectId, IdentityUser profile) {
        return keycloakAdminApi.getUserRepresentation(subjectId)
                .map(representation -> withProfile(representation, profile))
                .flatMap(representation -> keycloakAdminApi.updateUser(subjectId, representation))
                .onErrorMap(WebClientResponseException.Conflict.class,
                        e -> new BusinessException(BusinessErrorCode.USERNAME_TAKEN))
                .onErrorMap(WebClientResponseException.BadRequest.class,
                        e -> new BusinessException(BusinessErrorCode.PROFILE_INVALID));
    }

    /** Logs the subject out then deletes it, a 404 meaning done; bounded, as the admin client has no timeout. */
    @Override
    public Mono<Void> deleteSubject(String subjectId) {
        return Mono.defer(() -> keycloakAdminApi.logout(subjectId))
                .onErrorResume(WebClientResponseException.NotFound.class, e -> Mono.empty())
                .then(Mono.defer(() -> keycloakAdminApi.deleteUser(subjectId)
                        .onErrorResume(WebClientResponseException.NotFound.class, e -> Mono.empty())))
                .timeout(DELETE_TIMEOUT);
    }

    private static Map<String, Object> withProfile(Map<String, Object> representation, IdentityUser profile) {
        Map<String, Object> updated = new HashMap<>(representation);
        updated.put("username", profile.username());
        updated.put("firstName", profile.firstName());
        updated.put("lastName", profile.lastName());
        return updated;
    }
}
