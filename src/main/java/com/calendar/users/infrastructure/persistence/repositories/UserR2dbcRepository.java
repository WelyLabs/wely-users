package com.calendar.users.infrastructure.persistence.repositories;

import com.calendar.users.infrastructure.persistence.models.IdentityToCheckRow;
import com.calendar.users.infrastructure.persistence.models.UserAboutRow;
import com.calendar.users.infrastructure.persistence.models.UserPictureRow;
import com.calendar.users.infrastructure.persistence.models.UserPreferencesRow;
import com.calendar.users.infrastructure.persistence.models.entities.UserEntity;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.UUID;

@Repository
public interface UserR2dbcRepository extends ReactiveCrudRepository<UserEntity, UUID> {

    Mono<UserEntity> findById(UUID userId);

    Mono<UUID> findIdByKeycloakId(String keycloakId);

    Mono<Boolean> existsByUserNameAndHashtag(String userName, Integer hashTag);

    @Query("""
            SELECT id, keycloak_id
              FROM app_user
             WHERE identity_checked_at IS NULL
               AND joined_date < :createdBefore
             ORDER BY joined_date
             LIMIT :limit
            """)
    Flux<IdentityToCheckRow> findIdentitiesToCheck(LocalDateTime createdBefore, int limit);

    // Derived queries cannot update.
    @Modifying
    @Query("UPDATE app_user SET identity_checked_at = now() WHERE id = :id")
    Mono<Long> markIdentityChecked(UUID id);

    /** The name fields only: joined date, hashtag and picture are not the profile form's to change. */
    @Modifying
    @Query("UPDATE app_user SET user_name = :userName, first_name = :firstName, last_name = :lastName WHERE id = :id")
    Mono<Long> updateProfile(UUID id, String userName, String firstName, String lastName);

    @Query("SELECT keycloak_id FROM app_user WHERE id = :id")
    Mono<String> findKeycloakIdById(UUID id);

    /**
     * profile_pic_url holds a storage key for an upload ({@code avatars/...}), or a path the web app
     * serves itself; the name predates the change and migrations here are applied by hand.
     */
    @Modifying
    @Query("UPDATE app_user SET profile_pic_url = :reference WHERE id = :id")
    Mono<Long> updatePicture(UUID id, String reference);

    @Query("SELECT id, profile_pic_url FROM app_user WHERE id IN (:ids) AND profile_pic_url IS NOT NULL")
    Flux<UserPictureRow> findPictures(Collection<UUID> ids);

    @Query("SELECT bio, interests FROM app_user WHERE id = :id")
    Mono<UserAboutRow> findAboutById(UUID id);

    @Modifying
    @Query("UPDATE app_user SET bio = :bio, interests = :interests WHERE id = :id")
    Mono<Long> updateAbout(UUID id, String bio, String[] interests);

    @Query("SELECT theme FROM app_user WHERE id = :id")
    Mono<UserPreferencesRow> findPreferencesById(UUID id);

    @Modifying
    @Query("UPDATE app_user SET theme = :theme WHERE id = :id")
    Mono<Long> updatePreferences(UUID id, String theme);
}
