package com.calendar.users.domain.services;

import com.calendar.users.domain.models.BusinessUser;
import com.calendar.users.domain.models.IdentityUser;
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

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public Mono<BusinessUser> readProfile(UUID userId) {
        return userRepository.getBusinessUserByUserId(userId)
                .switchIfEmpty(Mono.error(new BusinessException(BusinessErrorCode.USER_NOT_FOUND)));
    }

    /** Read at every login by Keycloak's mapper. Users are created at registration, never here. */
    public Mono<UUID> findBusinessIdByKeycloakId(String keycloakId) {
        return userRepository.findIdByKeycloakId(keycloakId)
                .switchIfEmpty(Mono.error(new BusinessException(BusinessErrorCode.USER_NOT_FOUND)));
    }

    /**
     * Creates the user at registration, from the identity Keycloak sends.
     * Idempotent: a Keycloak id that already has a user returns it, so a retried call is harmless.
     */
    public Mono<UUID> provisionUser(String keycloakId, IdentityUser identityUser) {
        return userRepository.findIdByKeycloakId(keycloakId)
                .switchIfEmpty(Mono.defer(() -> createUser(keycloakId, identityUser)))
                // Two concurrent calls for one id: the loser hits the keycloak_id constraint.
                .onErrorResume(UserService::isAlreadyExists,
                        e -> userRepository.findIdByKeycloakId(keycloakId).switchIfEmpty(Mono.error(e)));
    }

    /** Creates the user with a free hashtag. */
    private Mono<UUID> createUser(String keycloakId, IdentityUser identityUser) {
        return generateUniqueHashtag(identityUser.username())
                .map(hashtag -> new BusinessUser(
                        null,
                        identityUser.username(),
                        hashtag,
                        identityUser.firstName(),
                        identityUser.lastName(),
                        null,
                        LocalDateTime.now()))
                .flatMap(newUser -> userRepository.save(newUser, keycloakId))
                .map(BusinessUser::id);
    }

    private static boolean isAlreadyExists(Throwable error) {
        return error instanceof BusinessException businessException
                && businessException.getErrorCode() == BusinessErrorCode.USER_ALREADY_EXISTS;
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
