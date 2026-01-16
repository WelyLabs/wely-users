package com.calendar.users.domain.ports;

import com.calendar.users.domain.models.BusinessUser;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface UserRepository {

    Mono<BusinessUser> save(BusinessUser businessUser, String keycloakId);

    Mono<BusinessUser> getBusinessUserByUserId(UUID UserId);

    Mono<Boolean> existsByUserNameAndHashtag(String userName, Integer hashTag);

    Mono<Integer> updateProfilePicUrl(String profilePicUrl, Long userId);

    Mono<UUID> findIdByKeycloakId(String keycloakId);

}
