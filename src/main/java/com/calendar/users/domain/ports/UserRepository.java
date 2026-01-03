package com.calendar.users.domain.ports;

import com.calendar.users.domain.models.BusinessUser;
import reactor.core.publisher.Mono;

public interface UserRepository {

    Mono<BusinessUser> save(BusinessUser businessUser, String keycloakId);

    Mono<BusinessUser> getBusinessUserByUserId(Long UserId);

    Mono<Boolean> existsByUserNameAndHashtag(String userName, Integer hashTag);

    Mono<Integer> updateProfilePicUrl(String profilePicUrl, Long userId);

    Mono<Long> findIdByKeycloakId(String keycloakId);

}
