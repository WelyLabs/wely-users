package com.calendar.users.domain.services;

import com.calendar.users.domain.models.BusinessUser;
import com.calendar.users.domain.ports.IdentityProvider;
import com.calendar.users.domain.ports.UserRepository;
import com.calendar.users.exception.BusinessErrorCode;
import com.calendar.users.exception.BusinessException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public class UserService {

    private static final int HASHTAG_MIN = 1000;
    private static final int HASHTAG_MAX_EXCLUSIVE = 10_000;
    private static final int MAX_HASHTAG_ATTEMPTS = 10;

    private final UserRepository userRepository;
    private final IdentityProvider identityProvider;

    public UserService(UserRepository userRepository, IdentityProvider identityProvider) {
        this.userRepository = userRepository;
        this.identityProvider = identityProvider;
    }

    public Mono<BusinessUser> readProfile(UUID userId) {
        return userRepository.getBusinessUserByUserId(userId)
                .switchIfEmpty(Mono.error(new BusinessException(BusinessErrorCode.USER_NOT_FOUND)));
    }

    /**
     * Translates a Keycloak id into the internal user id, creating the user on first sight.
     * The provisioning is deferred so that an existing user costs a single query.
     */
    public Mono<UUID> resolveInternalUserId(String keycloakId) {
        return userRepository.findIdByKeycloakId(keycloakId)
                .switchIfEmpty(Mono.defer(() -> provisionUser(keycloakId)));
    }

    /** Creates the user from its Keycloak identity, with a free hashtag. */
    private Mono<UUID> provisionUser(String keycloakId) {
        return identityProvider.getUser(keycloakId)
                .flatMap(identityUser -> generateUniqueHashtag(identityUser.username())
                        .map(hashtag -> new BusinessUser(
                                null,
                                identityUser.username(),
                                hashtag,
                                identityUser.firstName(),
                                identityUser.lastName(),
                                null,
                                LocalDateTime.now()))
                        .flatMap(newUser -> userRepository.save(newUser, keycloakId))
                        .map(BusinessUser::id));
    }

    /**
     * Draws a random hashtag that is free for this username, giving up after a few attempts.
     * The unique_user_identity constraint remains the final guarantee.
     */
    private Mono<Integer> generateUniqueHashtag(String userName) {
        return Flux.range(0, MAX_HASHTAG_ATTEMPTS)
                .map(attempt -> ThreadLocalRandom.current().nextInt(HASHTAG_MIN, HASHTAG_MAX_EXCLUSIVE))
                .concatMap(candidate -> userRepository.existsByUserNameAndHashtag(userName, candidate)
                        .filter(taken -> !taken)
                        .map(free -> candidate))
                .next()
                .switchIfEmpty(Mono.error(new BusinessException(BusinessErrorCode.HASHTAG_UNAVAILABLE)));
    }
}
