package com.calendar.users.domain.services;

import com.calendar.users.domain.models.BusinessUser;
import com.calendar.users.domain.models.IdentityToCheck;
import com.calendar.users.domain.models.IdentityUser;
import com.calendar.users.domain.models.OrphanSweep;
import com.calendar.users.domain.models.ProfileUpdate;
import com.calendar.users.domain.ports.IdentityProvider;
import com.calendar.users.domain.ports.TransactionRunner;
import com.calendar.users.domain.ports.UserRepository;
import com.calendar.users.exception.BusinessErrorCode;
import com.calendar.users.exception.BusinessException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public class UserService {

    private static final int HASHTAG_MIN = 1000;
    private static final int HASHTAG_MAX_EXCLUSIVE = 10_000;
    private static final int MAX_HASHTAG_ATTEMPTS = 10;

    private final UserRepository userRepository;
    private final IdentityProvider identityProvider;
    private final TransactionRunner transactionRunner;

    public UserService(UserRepository userRepository, IdentityProvider identityProvider,
                       TransactionRunner transactionRunner) {
        this.userRepository = userRepository;
        this.identityProvider = identityProvider;
        this.transactionRunner = transactionRunner;
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
     * Changes the user's username and names, here and at the identity provider, and tells the other
     * services (USER_MODIFIED).
     *
     * <p>No transaction spans PostgreSQL and the identity provider, so the order does the job of
     * one. The row and its outbox event are written first, inside a transaction still open; the
     * identity provider is called next; the commit comes last. The provider refusing — the
     * username is taken, the value breaks one of its rules — fails the transaction, and nothing
     * changed anywhere. What remains is the commit failing after the provider accepted: rare,
     * logged as a database error, and repaired by saving the profile again.
     *
     * <p>The username is lowercased because the identity provider stores it so: kept as typed, the
     * two would disagree on the name the user signs in with.
     */
    public Mono<BusinessUser> updateProfile(UUID userId, ProfileUpdate update) {
        String userName = update.userName().toLowerCase(Locale.ROOT);

        return transactionRunner.inTransaction(userRepository.getBusinessUserByUserId(userId)
                .zipWith(userRepository.findKeycloakIdByUserId(userId))
                .switchIfEmpty(Mono.error(new BusinessException(BusinessErrorCode.USER_NOT_FOUND)))
                .flatMap(found -> {
                    BusinessUser current = found.getT1();
                    BusinessUser changed = new BusinessUser(current.id(), userName, current.hashtag(),
                            update.firstName(), update.lastName(), current.profilePicUrl(), current.joinedDate());
                    return userRepository.updateProfile(changed)
                            .flatMap(saved -> identityProvider.updateProfile(found.getT2(),
                                            new IdentityUser(userName, update.firstName(), update.lastName()))
                                    .thenReturn(saved));
                }));
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

    /**
     * Checks users whose Keycloak account was never confirmed, oldest first. A user Keycloak does
     * not know is left by a registration Keycloak cancelled after wely-users had created it: it is
     * deleted. Each user is checked once; one failure does not stop the others.
     */
    public Mono<OrphanSweep> removeOrphans(LocalDateTime createdBefore, int limit) {
        return userRepository.findIdentitiesToCheck(createdBefore, limit)
                .concatMap(this::checkIdentity)
                .reduce(OrphanSweep.EMPTY, OrphanSweep::plus);
    }

    private Mono<OrphanSweep> checkIdentity(IdentityToCheck identity) {
        return identityProvider.getUser(identity.keycloakId())
                .map(found -> true)
                .defaultIfEmpty(false)
                .onErrorResume(UserService::isUserNotFound, e -> Mono.just(false))
                .flatMap(exists -> exists
                        ? userRepository.markIdentityChecked(identity.userId()).thenReturn(new OrphanSweep(1, 0, 0))
                        : userRepository.deleteOrphan(identity.userId()).thenReturn(new OrphanSweep(0, 1, 0)))
                // Keycloak or the database is down: left unchecked, retried on the next pass.
                .onErrorResume(e -> Mono.just(new OrphanSweep(0, 0, 1)));
    }

    private static boolean isUserNotFound(Throwable error) {
        return error instanceof BusinessException businessException
                && businessException.getErrorCode() == BusinessErrorCode.USER_NOT_FOUND;
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
