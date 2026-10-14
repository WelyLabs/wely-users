package com.calendar.users.domain.services;

import com.calendar.users.domain.models.BusinessUser;
import com.calendar.users.domain.models.FriendProfile;
import com.calendar.users.domain.models.IdentityToCheck;
import com.calendar.users.domain.models.IdentityUser;
import com.calendar.users.domain.models.OrphanSweep;
import com.calendar.users.domain.models.ProfileUpdate;
import com.calendar.users.domain.models.UserAbout;
import com.calendar.users.domain.models.UserPicture;
import com.calendar.users.domain.models.UserPreferences;
import com.calendar.users.domain.models.MediaProfile;
import com.calendar.users.domain.ports.FriendshipChecker;
import com.calendar.users.domain.ports.IdentityProvider;
import com.calendar.users.domain.ports.MediaStore;
import com.calendar.users.domain.ports.UserRepository;
import com.calendar.users.exception.BusinessErrorCode;
import com.calendar.users.exception.BusinessException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public class UserService {

    private static final int HASHTAG_MIN = 1000;
    private static final int HASHTAG_MAX_EXCLUSIVE = 10_000;
    private static final int MAX_HASHTAG_ATTEMPTS = 10;

    private final UserRepository userRepository;
    private final IdentityProvider identityProvider;
    private final MediaStore mediaStore;
    private final FriendshipChecker friendshipChecker;

    public UserService(UserRepository userRepository, IdentityProvider identityProvider, MediaStore mediaStore,
                       FriendshipChecker friendshipChecker) {
        this.userRepository = userRepository;
        this.identityProvider = identityProvider;
        this.mediaStore = mediaStore;
        this.friendshipChecker = friendshipChecker;
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
     * <p>No transaction spans PostgreSQL and the identity provider, so this is a small saga. The
     * identity provider goes first: refusing — the username is taken, a value breaks one of its
     * rules — it leaves nothing to undo. The row and its outbox event come next, in the
     * repository's own transaction. If the database refuses, the identity provider is given its
     * previous values back before the error goes on. Only that undo failing too leaves the two
     * apart: the original error carries the undo's as suppressed, and saving the profile again
     * repairs it.
     *
     * <p>The transaction stays where it is technical, in the adapter: the domain states what to
     * undo, not how a database commits.
     *
     * <p>The username is lowercased because the identity provider stores it so: kept as typed, the
     * two would disagree on the name the user signs in with.
     */
    public Mono<BusinessUser> updateProfile(UUID userId, ProfileUpdate update) {
        String userName = update.userName().toLowerCase(Locale.ROOT);

        return userRepository.getBusinessUserByUserId(userId)
                .zipWith(userRepository.findKeycloakIdByUserId(userId))
                .switchIfEmpty(Mono.error(new BusinessException(BusinessErrorCode.USER_NOT_FOUND)))
                .flatMap(found -> {
                    BusinessUser current = found.getT1();
                    String keycloakId = found.getT2();
                    BusinessUser changed = new BusinessUser(current.id(), userName, current.hashtag(),
                            update.firstName(), update.lastName(), current.profilePicUrl(), current.joinedDate());
                    IdentityUser before = new IdentityUser(current.userName(), current.firstName(), current.lastName());
                    IdentityUser after = new IdentityUser(userName, update.firstName(), update.lastName());

                    return identityProvider.updateProfile(keycloakId, after)
                            // Deferred: built eagerly, the write would be prepared even when the provider refused.
                            .then(Mono.defer(() -> userRepository.updateProfile(changed)
                                    .onErrorResume(error -> undo(keycloakId, before, error))));
                });
    }

    /** The user's bio and interests, as they wrote them; empty when they wrote none. */
    public Mono<UserAbout> about(UUID userId) {
        return userRepository.findAbout(userId).defaultIfEmpty(UserAbout.EMPTY);
    }

    /**
     * Another user's profile, for their friends only. An unknown user is 404 before anything else;
     * the viewer asking for themself is not sent to the social graph.
     */
    public Mono<FriendProfile> friendProfile(UUID viewerId, UUID userId) {
        return readProfile(userId)
                .flatMap(user -> isAllowed(viewerId, userId)
                        .filter(Boolean::booleanValue)
                        .switchIfEmpty(Mono.error(() -> new BusinessException(BusinessErrorCode.NOT_A_FRIEND)))
                        // Deferred: built eagerly, the bio would be read even for a refused viewer.
                        .then(Mono.defer(() -> about(userId)))
                        .map(about -> FriendProfile.of(user, about)));
    }

    private Mono<Boolean> isAllowed(UUID viewerId, UUID userId) {
        return viewerId.equals(userId)
                ? Mono.just(true)
                : friendshipChecker.areFriends(viewerId.toString(), userId.toString());
    }

    /**
     * Replaces the user's bio and interests. They live in this service only: the identity provider
     * and the other services have no use for them, so no event is sent.
     */
    public Mono<UserAbout> updateAbout(UUID userId, UserAbout about) {
        return userRepository.updateAbout(userId, about);
    }

    /** The user's preferences; the defaults for a user who never chose. */
    public Mono<UserPreferences> preferences(UUID userId) {
        return userRepository.findPreferences(userId).defaultIfEmpty(UserPreferences.DEFAULT);
    }

    /**
     * Replaces the user's preferences. Only the web app reads them, from this service: the identity
     * provider and the other services have no use for them, so no event is sent.
     */
    public Mono<UserPreferences> updatePreferences(UUID userId, UserPreferences preferences) {
        return userRepository.updatePreferences(userId, preferences);
    }

    /**
     * Replaces the user's picture and answers with a URL to show it.
     *
     * <p>The picture is normalized and stored under a key nobody can guess, then the user is pointed
     * at it (with USER_MODIFIED). The order keeps the user from ever pointing at a missing file: if
     * the database refuses, the new file is removed again. The previous file is removed last, and
     * only if it was an upload; failing to remove it leaves an orphan file, not a broken profile.
     *
     * <p>Completes empty when the new picture is saved but cannot be signed: the change happened,
     * so it is not reported as a failure, and the web app asks /avatars for the URL anyway.
     */
    public Mono<String> updatePicture(UUID userId, byte[] upload) {
        return userRepository.getBusinessUserByUserId(userId)
                .switchIfEmpty(Mono.error(new BusinessException(BusinessErrorCode.USER_NOT_FOUND)))
                .flatMap(current -> {
                    String key = UserPicture.STORED_PREFIX + userId + "/" + UUID.randomUUID() + ".jpg";
                    // Deferred steps: built eagerly, each would run even when the one before failed.
                    return mediaStore.store(key, upload, MediaProfile.AVATAR)
                            .flatMap(stored -> userRepository.updatePicture(userId, stored.key())
                                    // The file is stored but nobody points at it: remove it.
                                    .onErrorResume(error -> mediaStore.delete(stored.key())
                                            .onErrorResume(cleanup -> Mono.empty())
                                            .then(Mono.error(error)))
                                    .then(Mono.defer(() -> removePrevious(new UserPicture(userId, current.profilePicUrl()))))
                                    .then(Mono.defer(() -> mediaStore.urls(List.of(stored.key()))
                                            .mapNotNull(urls -> urls.get(stored.key()))
                                            .onErrorResume(error -> Mono.empty()))));
                });
    }

    /**
     * URLs for these users' pictures, for anyone signed in: the visibility profile pictures have
     * always had. Uploads are signed in one call; a path the web app serves is returned as it is.
     * Users without a picture are left out, and so are uploads the media store cannot sign right
     * now: one store outage should not take the demo avatars and the whole screen down with it.
     */
    public Mono<Map<UUID, String>> pictureUrls(Collection<UUID> userIds) {
        return userRepository.findPictures(userIds)
                .collectList()
                .flatMap(pictures -> {
                    List<String> keys = pictures.stream()
                            .filter(UserPicture::isStored)
                            .map(UserPicture::reference)
                            .toList();
                    Mono<Map<String, String>> signed = keys.isEmpty()
                            ? Mono.just(Map.of())
                            : mediaStore.urls(keys).onErrorResume(error -> Mono.just(Map.of()));
                    return signed.map(urls -> resolve(pictures, urls));
                });
    }

    /** User id to URL: signed for an upload, as it is for a served path, absent when unsigned. */
    private static Map<UUID, String> resolve(List<UserPicture> pictures, Map<String, String> signed) {
        Map<UUID, String> urls = new HashMap<>();
        for (UserPicture picture : pictures) {
            String url = picture.isStored() ? signed.get(picture.reference()) : picture.reference();
            if (url != null) {
                urls.put(picture.userId(), url);
            }
        }
        return urls;
    }

    private Mono<Void> removePrevious(UserPicture previous) {
        return previous.isStored()
                ? mediaStore.delete(previous.reference()).onErrorResume(error -> Mono.empty())
                : Mono.empty();
    }

    /** Gives the identity provider its previous values back, then fails with the original error. */
    private Mono<BusinessUser> undo(String keycloakId, IdentityUser previous, Throwable error) {
        return identityProvider.updateProfile(keycloakId, previous)
                .onErrorResume(undoFailed -> {
                    error.addSuppressed(undoFailed);
                    return Mono.empty();
                })
                .then(Mono.error(error));
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
