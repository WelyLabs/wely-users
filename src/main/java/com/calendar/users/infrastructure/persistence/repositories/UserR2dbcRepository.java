package com.calendar.users.infrastructure.persistence.repositories;

import com.calendar.users.infrastructure.persistence.models.IdentityToCheckRow;
import com.calendar.users.infrastructure.persistence.models.entities.UserEntity;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
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
}
