package com.calendar.users.domain.services;

import com.calendar.users.domain.models.BusinessUser;
import com.calendar.users.domain.ports.IdentityProvider;
import com.calendar.users.domain.ports.UserEventPublisher;
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
    private final UserEventPublisher userEventPublisher;

    public UserService(UserRepository userRepository, IdentityProvider identityProvider,
            UserEventPublisher userEventPublisher) {
        this.userRepository = userRepository;
        this.identityProvider = identityProvider;
        this.userEventPublisher = userEventPublisher;
    }

    public Mono<BusinessUser> readProfile(UUID userId) {
        return userRepository.getBusinessUserByUserId(userId)
                .switchIfEmpty(Mono.error(new BusinessException(BusinessErrorCode.USER_NOT_FOUND)));
    }

    public Mono<UUID> resolveInternalUserId(String keycloakId) {
        return userRepository.findIdByKeycloakId(keycloakId)
                .switchIfEmpty(
                        identityProvider.getUser(keycloakId)
                                .flatMap(keycloakUserResponse -> generateUniqueHashtag(keycloakUserResponse.username())
                                        .flatMap(hashtag -> {
                                            BusinessUser newUser = new BusinessUser(
                                                    null,
                                                    keycloakUserResponse.username(),
                                                    hashtag,
                                                    keycloakUserResponse.firstName(),
                                                    keycloakUserResponse.lastName(),
                                                    null,
                                                    LocalDateTime.now());

                                            return userRepository.save(newUser, keycloakId)
                                                    .flatMap(userEventPublisher::publishUserCreatedEvent);
                                        })));
    }

    /**
     * Draws a hashtag that is free for this username, so the pair forms the public
     * handle {@code Name#1234}.
     *
     * <p>Random draw rather than a shared counter, which would serialise every
     * signup. Bounded to {@code MAX_HASHTAG_ATTEMPTS}: the previous version recursed
     * without a limit, so a saturated username looped forever against the database.
     * {@code concatMap} keeps the draws lazy — the first free candidate stops the
     * sequence.
     *
     * <p>Uniqueness is ultimately enforced by the {@code unique_user_identity}
     * constraint; this only avoids hitting it on the common path.
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
