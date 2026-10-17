package com.calendar.users.domain.ports;

import com.calendar.users.domain.models.AccountErasure;
import com.calendar.users.domain.models.AccountState;
import com.calendar.users.domain.models.BusinessUser;
import com.calendar.users.domain.models.DeletedUser;
import com.calendar.users.domain.models.UserAbout;
import com.calendar.users.domain.models.IdentityToCheck;
import com.calendar.users.domain.models.Registration;
import com.calendar.users.domain.models.UserPicture;
import com.calendar.users.domain.models.UserPreferences;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.UUID;

/** Users and their tombstones; reads and updates see live users only. */
public interface UserRepository {

    /** Saves a new user. Other services are told about it (USER_CREATED). */
    Mono<BusinessUser> save(BusinessUser businessUser, String keycloakId);

    Mono<BusinessUser> getBusinessUserByUserId(UUID userId);

    /** True when a live user holds the handle or the handle is retired. */
    Mono<Boolean> existsByUserNameAndHashtag(String userName, Integer hashTag);

    Mono<UUID> findIdByKeycloakId(String keycloakId);

    /** Oldest first: users created before {@code createdBefore} whose account was never confirmed. */
    Flux<IdentityToCheck> findIdentitiesToCheck(LocalDateTime createdBefore, int limit);

    Mono<Void> markIdentityChecked(UUID userId);

    /** Deletes a live user left by a cancelled registration and tells other services (USER_DELETED). */
    Mono<Void> deleteOrphan(UUID userId);

    /**
     * Writes the new names and USER_MODIFIED, plus USER_BIRTHDAY when a birth date is set (the name it
     * shows may have changed); USERNAME_TAKEN for a handle taken, or retired on a rename.
     */
    Mono<BusinessUser> updateProfile(BusinessUser businessUser, String previousUserName);

    /** Stores the birth date, marks the signup completed and writes USER_BIRTHDAY; USER_NOT_FOUND for an unknown user. */
    Mono<BusinessUser> completeRegistration(UUID userId, Registration registration);

    /** The identity provider's id for this user. */
    Mono<String> findKeycloakIdByUserId(UUID userId);

    /** Points the user at a new picture and tells other services (USER_MODIFIED). */
    Mono<BusinessUser> updatePicture(UUID userId, String reference);

    /** The pictures of those of these users who have one. */
    Flux<UserPicture> findPictures(Collection<UUID> userIds);


    /** The user's bio and interests; empty when they wrote none. */
    Mono<UserAbout> findAbout(UUID userId);

    /** Replaces the user's bio and interests; fails with USER_NOT_FOUND for an unknown user. */
    Mono<UserAbout> updateAbout(UUID userId, UserAbout about);

    /** The user's preferences; empty for an unknown user. */
    Mono<UserPreferences> findPreferences(UUID userId);

    /** Replaces the user's preferences; fails with USER_NOT_FOUND for an unknown user. */
    Mono<UserPreferences> updatePreferences(UUID userId, UserPreferences preferences);

    /**
     * Turns the user into a tombstone, retires their handle, queues the identity and media cleanup, drops
     * the user's other outbox events and appends USER_DELETED, all in one transaction.
     */
    Mono<AccountErasure> eraseAccount(UUID userId, Instant deletedAt);

    /** Live or tombstone; empty for an unknown id. */
    Mono<AccountState> findAccountState(UUID userId);

    /** Those of these ids that are tombstones. */
    Flux<UUID> findDeletedIds(Collection<UUID> userIds);

    /** Tombstones in (deletedAt, userId) order, after the cursor, which may be null. */
    Flux<DeletedUser> findDeleted(DeletedUser after, int limit);
}
