package com.calendar.users.domain.services;

import com.calendar.users.domain.models.BusinessUser;
import com.calendar.users.domain.ports.IdentityProvider;
import com.calendar.users.domain.ports.TransactionBoundary;
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
    private final TransactionBoundary transactionBoundary;

    public UserService(UserRepository userRepository, IdentityProvider identityProvider,
            UserEventPublisher userEventPublisher, TransactionBoundary transactionBoundary) {
        this.userRepository = userRepository;
        this.identityProvider = identityProvider;
        this.userEventPublisher = userEventPublisher;
        this.transactionBoundary = transactionBoundary;
    }

    public Mono<BusinessUser> readProfile(UUID userId) {
        return userRepository.getBusinessUserByUserId(userId)
                .switchIfEmpty(Mono.error(new BusinessException(BusinessErrorCode.USER_NOT_FOUND)));
    }

    /**
     * Translates a Keycloak subject into the internal business id, provisioning the
     * user on first sight.
     *
     * <p>Called by the Keycloak protocol mapper while a token is being minted, so the
     * common path — the user already exists — must stay a single query. Hence the
     * {@link Mono#defer}: passing the provisioning chain directly to
     * {@code switchIfEmpty} assembles it on every call, which means asking the identity
     * provider for a user that was already found.
     */
    public Mono<UUID> resolveInternalUserId(String keycloakId) {
        return userRepository.findIdByKeycloakId(keycloakId)
                .switchIfEmpty(Mono.defer(() -> provisionUser(keycloakId)));
    }

    /**
     * Creates the user and records the creation event as one unit.
     *
     * <p>Saving and publishing used to be two steps in a row, which left a window: a user
     * could be in PostgreSQL while {@code USER_CREATED} never reached Kafka, and nothing
     * in the system would notice. The account existed and the social graph had no node
     * for it — permanently, since nothing retries.
     *
     * <p>Both writes now go to the same database inside one transaction, so they commit
     * together or not at all. The publisher no longer talks to the broker: it appends to
     * the outbox, and a relay carries the row to Kafka afterwards. See
     * {@code OutboxUserEventPublisherAdapter}.
     *
     * <p>What stays outside the boundary matters as much as what is inside. Fetching the
     * identity and drawing a free hashtag are reads and a network call; holding a
     * database transaction across them would tie up a connection for a Keycloak round
     * trip on every signup.
     */
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
                        .flatMap(newUser -> transactionBoundary.atomically(
                                userRepository.save(newUser, keycloakId)
                                        .flatMap(userEventPublisher::publishUserCreatedEvent))));
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
