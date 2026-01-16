package com.calendar.users.infrastructure.persistence.repositories;

import com.calendar.users.infrastructure.persistence.models.entities.UserEntity;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Repository
public interface UserR2dbcRepository extends ReactiveCrudRepository<UserEntity, UUID> {

    Mono<UserEntity> findById(UUID userId);

    Mono<UUID> findIdByKeycloakId(String keycloakId);

    Mono<Boolean> existsByUserNameAndHashtag(String userName, Integer hashTag);

    @Modifying
    @Query("UPDATE app_user SET profile_pic_url = :url WHERE id = :userId")
    Mono<Integer> updateProfilePicUrlByKeycloakId(String url, Long userId);
}
