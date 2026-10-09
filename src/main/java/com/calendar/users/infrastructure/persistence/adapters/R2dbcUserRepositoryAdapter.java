package com.calendar.users.infrastructure.persistence.adapters;

import com.calendar.users.domain.models.BusinessUser;
import com.calendar.users.domain.ports.UserRepository;
import com.calendar.users.exception.BusinessErrorCode;
import com.calendar.users.exception.BusinessException;
import com.calendar.users.infrastructure.persistence.mappers.UserEntityMapper;
import com.calendar.users.infrastructure.persistence.models.entities.UserEntity;
import com.calendar.users.infrastructure.persistence.repositories.UserR2dbcRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** Persists users through R2DBC. Unexpected failures are mapped by {@code InfrastructureErrorAspect}. */
@Component
public class R2dbcUserRepositoryAdapter implements UserRepository {

    private final UserR2dbcRepository userR2dbcRepository;
    private final UserEntityMapper userEntityMapper;
    private final UserCreatedOutboxWriter userCreatedOutboxWriter;

    public R2dbcUserRepositoryAdapter(UserR2dbcRepository userR2dbcRepository,
                                      UserEntityMapper userEntityMapper,
                                      UserCreatedOutboxWriter userCreatedOutboxWriter) {
        this.userR2dbcRepository = userR2dbcRepository;
        this.userEntityMapper = userEntityMapper;
        this.userCreatedOutboxWriter = userCreatedOutboxWriter;
    }

    /**
     * Saves a new user and its USER_CREATED outbox event in one transaction:
     * both rows are committed, or neither is.
     */
    @Override
    @Transactional
    public Mono<BusinessUser> save(BusinessUser businessUser, String keycloakId) {
        UserEntity userEntity = userEntityMapper.toUserEntity(businessUser);
        userEntity.setKeycloakId(keycloakId);

        return userR2dbcRepository.save(userEntity)
                .map(userEntityMapper::toBusinessUser)
                // A taken (user_name, hashtag) pair is a business error, not a database incident.
                .onErrorMap(DataIntegrityViolationException.class,
                        e -> new BusinessException(BusinessErrorCode.USER_ALREADY_EXISTS))
                .flatMap(saved -> userCreatedOutboxWriter.write(saved).thenReturn(saved));
    }

    @Override
    public Mono<UUID> findIdByKeycloakId(String keycloakId) {
        return userR2dbcRepository.findIdByKeycloakId(keycloakId);
    }

    @Override
    public Mono<Boolean> existsByUserNameAndHashtag(String userName, Integer hashTag) {
        return userR2dbcRepository.existsByUserNameAndHashtag(userName, hashTag);
    }

    @Override
    public Mono<BusinessUser> getBusinessUserByUserId(UUID userId) {
        return userR2dbcRepository.findById(userId).map(userEntityMapper::toBusinessUser);
    }
}
