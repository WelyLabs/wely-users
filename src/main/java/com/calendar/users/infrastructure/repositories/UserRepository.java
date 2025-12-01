package com.calendar.users.infrastructure.repositories;

import com.calendar.users.infrastructure.models.entities.UserEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public interface UserRepository extends ReactiveCrudRepository<UserEntity, Long> {

    @Query("SELECT u.* FROM app_user u " + // Sélectionne toutes les colonnes de la table 'app_user'
            "INNER JOIN user_friends f ON u.id = f.friend_id " + // Jointure sur l'ID de l'ami
            "WHERE f.user_id = :userId")
    Flux<UserEntity> findFriendByUserId(Long userId, Pageable pageable);

    Mono<UserEntity> findByKeycloakId(String keycloakId);
}
