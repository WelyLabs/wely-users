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
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Persists users through R2DBC.
 *
 * <p>Generic failure handling — logging, and mapping anything unexpected to
 * {@code TechnicalException(DATABASE_ERROR)} — lives in
 * {@link com.calendar.users.infrastructure.observability.InfrastructureErrorAspect}, which
 * wraps each returned publisher. What remains here is the one translation that is a
 * business rule rather than an incident.
 */
@Component
public class R2dbcUserRepositoryAdapter implements UserRepository {

    private final UserR2dbcRepository userR2dbcRepository;
    private final UserEntityMapper userEntityMapper;

    public R2dbcUserRepositoryAdapter(UserR2dbcRepository userR2dbcRepository,
                                    UserEntityMapper userEntityMapper) {
        this.userR2dbcRepository = userR2dbcRepository;
        this.userEntityMapper = userEntityMapper;
    }

    @Override
    public Mono<BusinessUser> save(BusinessUser businessUser, String keycloakId) {
        UserEntity userEntity = userEntityMapper.toUserEntity(businessUser);
        userEntity.setKeycloakId(keycloakId);

        return userR2dbcRepository.save(userEntity)
                .map(userEntityMapper::toBusinessUser)
                // Violating unique_user_identity is not a database incident: it means the
                // (user_name, hashtag) pair is taken. Mapped here because it is specific to
                // this operation, and applied before the aspect, which lets business
                // failures through.
                .onErrorMap(DataIntegrityViolationException.class,
                        e -> new BusinessException(BusinessErrorCode.USER_ALREADY_EXISTS));
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
