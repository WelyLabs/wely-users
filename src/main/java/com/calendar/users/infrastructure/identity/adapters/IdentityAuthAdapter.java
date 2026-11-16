package com.calendar.users.infrastructure.identity.adapters;

import com.calendar.users.domain.models.IdentityUser;
import com.calendar.users.domain.ports.IdentityProvider;
import com.calendar.users.exception.BusinessErrorCode;
import com.calendar.users.exception.BusinessException;
import com.calendar.users.infrastructure.identity.api.Auth0ManagementApi;
import com.calendar.users.infrastructure.identity.mappers.IdentityUserMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Reads and changes users through Auth0's Management API and hands the domain its own model.
 *
 * <p>This class is where the vendor stops: {@code Auth0UserResponse} does not travel past it.
 * Failures are logged and mapped to {@code TechnicalException(IDENTITY_PROVIDER_ERROR)} by
 * {@link com.calendar.users.infrastructure.observability.InfrastructureErrorAspect}; a 404 becomes
 * USER_NOT_FOUND there, which the orphan sweep reads as "the subject is gone". Anything else,
 * a 429 of the rate limit included, stays a technical error and is retried later.
 */
@Slf4j
@Component
public class IdentityAuthAdapter implements IdentityProvider {

    static final Duration DELETE_TIMEOUT = Duration.ofSeconds(10);
    private static final String USERNAME_IN_USE = "already exists";
    // Only database-connection users carry an editable root profile; google-oauth2|, github|... are owned by their provider.
    private static final String DATABASE_SUBJECT_PREFIX = "auth0|";

    private final Auth0ManagementApi managementApi;
    private final IdentityUserMapper identityUserMapper;

    public IdentityAuthAdapter(Auth0ManagementApi managementApi,
                               IdentityUserMapper identityUserMapper) {
        this.managementApi = managementApi;
        this.identityUserMapper = identityUserMapper;
    }

    @Override
    public Mono<IdentityUser> getUser(String subjectId) {
        return managementApi.getUser(subjectId).map(identityUserMapper::toIdentityUser);
    }

    /**
     * A partial PATCH of the three fields. A null name is left out rather than sent: Auth0 types
     * them as strings, and wely-users, not Auth0, is where profiles are read.
     *
     * <p>Auth0's refusals are the user's to fix, so they are business errors here; the aspect
     * lets those through and turns anything else into IDENTITY_PROVIDER_ERROR.
     *
     * <p>A social subject is skipped: Auth0 refuses to PATCH the root attributes of a user whose
     * profile comes from Google or GitHub, and the Wely profile alone is the one read.
     */
    @Override
    public Mono<Void> updateProfile(String subjectId, IdentityUser profile) {
        if (!subjectId.startsWith(DATABASE_SUBJECT_PREFIX)) {
            return Mono.empty();
        }
        return Mono.defer(() -> managementApi.updateUser(subjectId, changes(profile)))
                .onErrorMap(WebClientResponseException.Conflict.class,
                        e -> new BusinessException(BusinessErrorCode.USERNAME_TAKEN))
                // Auth0 answers a taken username with 400 "The specified new username already exists".
                .onErrorMap(WebClientResponseException.BadRequest.class,
                        e -> new BusinessException(e.getResponseBodyAsString().contains(USERNAME_IN_USE)
                                ? BusinessErrorCode.USERNAME_TAKEN
                                : BusinessErrorCode.PROFILE_INVALID));
    }

    /**
     * Ends the subject's sessions then deletes it, a 404 meaning done; bounded, as the client has no
     * timeout. A 403 on the sessions call is tolerated: the endpoint or its scope may be missing from
     * the plan, and deleting the user still revokes its refresh tokens.
     */
    @Override
    public Mono<Void> deleteSubject(String subjectId) {
        return Mono.defer(() -> managementApi.deleteSessions(subjectId))
                .onErrorResume(WebClientResponseException.NotFound.class, e -> Mono.empty())
                .onErrorResume(WebClientResponseException.Forbidden.class, e -> {
                    log.warn("Auth0 refused to end the sessions (403), deleting the user anyway");
                    return Mono.empty();
                })
                .then(Mono.defer(() -> managementApi.deleteUser(subjectId)
                        .onErrorResume(WebClientResponseException.NotFound.class, e -> Mono.empty())))
                .timeout(DELETE_TIMEOUT);
    }

    private static Map<String, Object> changes(IdentityUser profile) {
        Map<String, Object> changes = new HashMap<>();
        changes.put("username", profile.username());
        if (profile.firstName() != null) {
            changes.put("given_name", profile.firstName());
        }
        if (profile.lastName() != null) {
            changes.put("family_name", profile.lastName());
        }
        return changes;
    }
}
