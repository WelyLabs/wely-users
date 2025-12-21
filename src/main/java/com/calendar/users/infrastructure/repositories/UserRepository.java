package com.calendar.users.infrastructure.repositories;

import com.calendar.users.infrastructure.models.entities.UserEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public interface UserRepository extends ReactiveCrudRepository<UserEntity, Long> {

    Mono<UserEntity> findById(String userId);

    Mono<Long> findIdByKeycloakId(String keycloakId);

    @Modifying
    @Query("UPDATE app_user SET profile_pic_url = :url WHERE keycloak_id = :keycloakId")
    Mono<Integer> updateProfilePicUrlByKeycloakId(String url, String keycloakId);
}
