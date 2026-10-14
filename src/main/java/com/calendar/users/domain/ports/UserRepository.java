package com.calendar.users.domain.ports;

import com.calendar.users.domain.models.BusinessUser;
import com.calendar.users.domain.models.UserAbout;
import com.calendar.users.domain.models.IdentityToCheck;
import com.calendar.users.domain.models.UserPicture;
import com.calendar.users.domain.models.UserPreferences;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.UUID;

public interface UserRepository {

    /** Saves a new user. Other services are told about it (USER_CREATED). */
    Mono<BusinessUser> save(BusinessUser businessUser, String keycloakId);

    Mono<BusinessUser> getBusinessUserByUserId(UUID userId);

    Mono<Boolean> existsByUserNameAndHashtag(String userName, Integer hashTag);

    Mono<UUID> findIdByKeycloakId(String keycloakId);

    /** Oldest first: users created before {@code createdBefore} whose account was never confirmed. */
    Flux<IdentityToCheck> findIdentitiesToCheck(LocalDateTime createdBefore, int limit);

    Mono<Void> markIdentityChecked(UUID userId);

    /** Deletes a user left by a cancelled registration. Other services are told (USER_DELETED). */
    Mono<Void> deleteOrphan(UUID userId);

    /**
     * Writes a user's new name and username, and tells other services (USER_MODIFIED). Fails with
     * USERNAME_TAKEN if the (username, hashtag) pair is someone else's.
     */
    Mono<BusinessUser> updateProfile(BusinessUser businessUser);

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
}
