package com.calendar.users.infrastructure.persistence.repositories;

import com.calendar.users.infrastructure.persistence.models.entities.UserEntity;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

@Repository
public interface UserRepository extends ReactiveCrudRepository<UserEntity, Long> {

    Mono<UserEntity> findById(Long userId);

    Mono<Long> findIdByKeycloakId(String keycloakId);

    Mono<Boolean> existsByUserNameAndHashtag(String userName, Integer hashTag);

    @Modifying
    @Query("UPDATE app_user SET profile_pic_url = :url WHERE id = :userId")
    Mono<Integer> updateProfilePicUrlByKeycloakId(String url, Long userId);
}
