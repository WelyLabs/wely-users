package com.calendar.users.infrastructure.persistence.adapters;

import com.calendar.users.domain.models.BusinessUser;
import com.calendar.users.domain.ports.UserRepository;
import com.calendar.users.exception.BusinessErrorCode;
import com.calendar.users.exception.BusinessException;
import com.calendar.users.exception.TechnicalErrorCode;
import com.calendar.users.exception.TechnicalException;
import com.calendar.users.infrastructure.persistence.mappers.UserEntityMapper;
import com.calendar.users.infrastructure.persistence.models.entities.UserEntity;
import com.calendar.users.infrastructure.persistence.repositories.UserR2dbcRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Slf4j
@Component
public class JpaUserRepositoryAdapter implements UserRepository {

    private final UserR2dbcRepository userR2dbcRepository;
    private final UserEntityMapper userEntityMapper;

    public JpaUserRepositoryAdapter(UserR2dbcRepository userR2dbcRepository, UserEntityMapper userEntityMapper) {
        this.userR2dbcRepository = userR2dbcRepository;
        this.userEntityMapper = userEntityMapper;
    }

    public Mono<BusinessUser> save(BusinessUser businessUser, String keycloakId) {
        UserEntity userEntity = userEntityMapper.toUserEntity(businessUser);
        userEntity.setKeycloakId(keycloakId);
        return userR2dbcRepository.save(userEntity)
                .map(userEntityMapper::toBusinessUser)
                .onErrorMap(DataIntegrityViolationException.class, e -> {
                    log.error("Erreur de contrainte DB: {}", e.getMessage());
                    return new BusinessException(BusinessErrorCode.USER_ALREADY_EXISTS);
                })
                .onErrorMap(e -> !(e instanceof BusinessException),
                        e -> {
                    log.error("Erreur lors de l'enregistrement de l'utilisateur en base : {}", e.getMessage());
                    return new TechnicalException(TechnicalErrorCode.DATABASE_ERROR);
                });

    }

    public Mono<UUID> findIdByKeycloakId(String keycloakId) {
        return userR2dbcRepository.findIdByKeycloakId(keycloakId)
                .onErrorMap(e -> {
                    log.error("Erreur lors de la récupération de l'id via l'id Keycloak : {}", e.getMessage());
                    return new TechnicalException(TechnicalErrorCode.DATABASE_ERROR);
                });
    }

    public Mono<Boolean> existsByUserNameAndHashtag(String userName, Integer hashTag) {
        return userR2dbcRepository.existsByUserNameAndHashtag(userName, hashTag)
                .onErrorMap(e -> {
                    log.error("Erreur lors de la vérification de l'existance de l'utilisateur via son usertag : {}", e.getMessage());
                    return new TechnicalException(TechnicalErrorCode.DATABASE_ERROR);
                });
    }

    public Mono<BusinessUser> getBusinessUserByUserId(UUID userId) {
        return userR2dbcRepository.findById(userId).map(userEntityMapper::toBusinessUser)
                .onErrorMap(e -> {
                    log.error("Erreur lors de la récupération de l'utilisateur via son id : {}", e.getMessage());
                    return new TechnicalException(TechnicalErrorCode.DATABASE_ERROR);
                });
    }

    // todo : transférer le code dans un service dédié
    public Mono<Integer> updateProfilePicUrl(String profilePicUrl, Long userId) {
        return userR2dbcRepository.updateProfilePicUrlByKeycloakId(profilePicUrl, userId);
    }
}
