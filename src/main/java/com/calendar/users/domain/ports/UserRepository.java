package com.calendar.users.domain.ports;

import com.calendar.users.domain.models.BusinessUser;
import com.calendar.users.domain.models.IdentityToCheck;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
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

}
