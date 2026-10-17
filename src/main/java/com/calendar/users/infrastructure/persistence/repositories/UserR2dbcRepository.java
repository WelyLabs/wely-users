package com.calendar.users.infrastructure.persistence.repositories;

import com.calendar.users.infrastructure.persistence.models.DeletedUserRow;
import com.calendar.users.infrastructure.persistence.models.DeletionLockRow;
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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.UUID;

/** Spring Data access to {@code app_user}; reads and updates of a user skip tombstones (deleted_at set). */
@Repository
public interface UserR2dbcRepository extends ReactiveCrudRepository<UserEntity, UUID> {

    @Query("SELECT * FROM app_user WHERE id = :id AND deleted_at IS NULL")
    Mono<UserEntity> findLiveById(UUID id);

    @Query("SELECT id FROM app_user WHERE keycloak_id = :keycloakId AND deleted_at IS NULL")
    Mono<UUID> findIdByKeycloakId(String keycloakId);

    /** A live user's handle, or a retired one: either way it is not free. */
    @Query("""
            SELECT EXISTS (SELECT 1 FROM app_user WHERE user_name = :userName AND hashtag = :hashTag)
                OR EXISTS (SELECT 1 FROM retired_handle
                            WHERE handle_digest = retired_handle_digest(:userName, :hashTag))
            """)
    Mono<Boolean> existsByUserNameAndHashtag(String userName, Integer hashTag);

    @Query("""
            SELECT id, keycloak_id
              FROM app_user
             WHERE identity_checked_at IS NULL
               AND deleted_at IS NULL
               AND joined_date < :createdBefore
             ORDER BY joined_date
             LIMIT :limit
            """)
    Flux<IdentityToCheckRow> findIdentitiesToCheck(LocalDateTime createdBefore, int limit);

    // Derived queries cannot update.
    @Modifying
    @Query("UPDATE app_user SET identity_checked_at = now() WHERE id = :id AND deleted_at IS NULL")
    Mono<Long> markIdentityChecked(UUID id);

    /** Never a tombstone: an orphan check read before the deletion must not hard-delete it. */
    @Modifying
    @Query("DELETE FROM app_user WHERE id = :id AND deleted_at IS NULL")
    Mono<Long> deleteLiveById(UUID id);

    /** The name fields only: joined date, hashtag and picture are not the profile form's to change. */
    @Modifying
    @Query("""
            UPDATE app_user SET user_name = :userName, first_name = :firstName, last_name = :lastName
             WHERE id = :id AND deleted_at IS NULL
            """)
    Mono<Long> updateProfile(UUID id, String userName, String firstName, String lastName);

    /** Signup completion: stores the birth date and marks the step done. */
    @Modifying
    @Query("""
            UPDATE app_user SET birth_date = :birthDate, registration_completed = TRUE
             WHERE id = :id AND deleted_at IS NULL
            """)
    Mono<Long> completeRegistration(UUID id, LocalDate birthDate);

    @Query("SELECT keycloak_id FROM app_user WHERE id = :id AND deleted_at IS NULL")
    Mono<String> findKeycloakIdById(UUID id);

    /**
     * profile_pic_url holds a storage key for an upload ({@code avatars/...}), or a path the web app
     * serves itself; the name predates the change and migrations here are applied by hand.
     */
    @Modifying
    @Query("UPDATE app_user SET profile_pic_url = :reference WHERE id = :id AND deleted_at IS NULL")
    Mono<Long> updatePicture(UUID id, String reference);

    // A tombstone has no picture (app_user_tombstone_check), so it is never returned.
    @Query("SELECT id, profile_pic_url FROM app_user WHERE id IN (:ids) AND profile_pic_url IS NOT NULL")
    Flux<UserPictureRow> findPictures(Collection<UUID> ids);

    @Query("SELECT bio, interests FROM app_user WHERE id = :id AND deleted_at IS NULL")
    Mono<UserAboutRow> findAboutById(UUID id);

    @Modifying
    @Query("UPDATE app_user SET bio = :bio, interests = :interests WHERE id = :id AND deleted_at IS NULL")
    Mono<Long> updateAbout(UUID id, String bio, String[] interests);

    @Query("SELECT theme FROM app_user WHERE id = :id AND deleted_at IS NULL")
    Mono<UserPreferencesRow> findPreferencesById(UUID id);

    @Modifying
    @Query("UPDATE app_user SET theme = :theme WHERE id = :id AND deleted_at IS NULL")
    Mono<Long> updatePreferences(UUID id, String theme);

    /** Locks the row for the deletion transaction, tombstone or not. */
    @Query("SELECT id, keycloak_id, deleted_at FROM app_user WHERE id = :id FOR UPDATE")
    Mono<DeletionLockRow> lockForDeletion(UUID id);

    /** Clears every personal field; the placeholders are those app_user_tombstone_check requires. */
    @Modifying
    @Query("""
            UPDATE app_user
               SET keycloak_id = 'deleted:' || id::text,
                   user_name = 'deleted-' || id::text,
                   hashtag = 0,
                   first_name = NULL,
                   last_name = NULL,
                   profile_pic_url = NULL,
                   bio = NULL,
                   interests = '{}',
                   birth_date = NULL,
                   theme = 'SYSTEM',
                   joined_date = (:deletedAt AT TIME ZONE 'UTC'),
                   identity_checked_at = COALESCE(identity_checked_at, :deletedAt),
                   deleted_at = :deletedAt
             WHERE id = :id
               AND deleted_at IS NULL
            """)
    Mono<Long> tombstone(UUID id, OffsetDateTime deletedAt);

    /** Empty for an unknown id. */
    @Query("SELECT deleted_at IS NOT NULL FROM app_user WHERE id = :id")
    Mono<Boolean> isDeleted(UUID id);

    @Query("SELECT id FROM app_user WHERE id IN (:ids) AND deleted_at IS NOT NULL")
    Flux<UUID> findDeletedIds(Collection<UUID> ids);

    @Query("""
            SELECT id, deleted_at
              FROM app_user
             WHERE deleted_at IS NOT NULL
             ORDER BY deleted_at, id
             LIMIT :limit
            """)
    Flux<DeletedUserRow> findDeletedFirst(int limit);

    @Query("""
            SELECT id, deleted_at
              FROM app_user
             WHERE deleted_at IS NOT NULL
               AND (deleted_at, id) > (:afterDeletedAt, :afterId)
             ORDER BY deleted_at, id
             LIMIT :limit
            """)
    Flux<DeletedUserRow> findDeletedAfter(OffsetDateTime afterDeletedAt, UUID afterId, int limit);
}
