package com.calendar.users.domain.ports;

import com.calendar.users.domain.models.BusinessUser;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface UserRepository {

    /** Saves a new user. Other services are told about it (USER_CREATED). */
    Mono<BusinessUser> save(BusinessUser businessUser, String keycloakId);

    Mono<BusinessUser> getBusinessUserByUserId(UUID userId);

    Mono<Boolean> existsByUserNameAndHashtag(String userName, Integer hashTag);

    Mono<UUID> findIdByKeycloakId(String keycloakId);

}
