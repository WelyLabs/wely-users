package com.calendar.users.infrastructure.adapters;

import com.calendar.users.domain.models.BusinessUser;
import com.calendar.users.domain.ports.UserRepositoryPort;
import com.calendar.users.exception.BusinessErrorCode;
import com.calendar.users.exception.BusinessException;
import com.calendar.users.exception.TechnicalErrorCode;
import com.calendar.users.exception.TechnicalException;
import com.calendar.users.infrastructure.mappers.UserEntityMapper;
import com.calendar.users.infrastructure.models.entities.UserEntity;
import com.calendar.users.infrastructure.repositories.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Slf4j
@Component
public class UserRepositoryAdapter implements UserRepositoryPort {

    private final UserRepository userRepository;
    private final UserEntityMapper userEntityMapper;

    public UserRepositoryAdapter(UserRepository userRepository, UserEntityMapper userEntityMapper) {
        this.userRepository = userRepository;
        this.userEntityMapper = userEntityMapper;
    }

    public Mono<BusinessUser> save(BusinessUser businessUser, String keycloakId) {
        UserEntity userEntity = userEntityMapper.toUserEntity(businessUser);
        userEntity.setKeycloakId(keycloakId);
        return userRepository.save(userEntity)
                .map(userEntityMapper::toBusinessUser)
                .onErrorMap(DataIntegrityViolationException.class, e -> {
                    log.error("Erreur de contrainte DB: {}", e.getMessage());
                    return new BusinessException(BusinessErrorCode.USER_ALREADY_EXISTS);
                })
                .onErrorMap(e -> {
                    log.error("Erreur lors de l'enregistrement de l'utilisateur en base : {}", e.getMessage());
                    return new TechnicalException(TechnicalErrorCode.DATABASE_ERROR);
                });

    }

    public Mono<Long> findIdByKeycloakId(String keycloakId) {
        return userRepository.findIdByKeycloakId(keycloakId)
                .onErrorMap(e -> {
                    log.error("Erreur lors de la récupération de l'id via l'id Keycloak : {}", e.getMessage());
                    return new TechnicalException(TechnicalErrorCode.DATABASE_ERROR);
                });
    }

    public Mono<Boolean> existsByUserNameAndHashtag(String userName, Integer hashTag) {
        return userRepository.existsByUserNameAndHashtag(userName, hashTag)
                .onErrorMap(e -> {
                    log.error("Erreur lors de la vérification de l'existance de l'utilisateur via son usertag : {}", e.getMessage());
                    return new TechnicalException(TechnicalErrorCode.DATABASE_ERROR);
                });
    }

    public Mono<BusinessUser> getBusinessUserByUserId(Long userId) {
        return userRepository.findById(userId).map(userEntityMapper::toBusinessUser)
                .onErrorMap(e -> {
                    log.error("Erreur lors de la récupération de l'utilisateur via son id : {}", e.getMessage());
                    return new TechnicalException(TechnicalErrorCode.DATABASE_ERROR);
                });
    }

    // todo : transférer le code dans un service dédié
    public Mono<Integer> updateProfilePicUrl(String profilePicUrl, Long userId) {
        return userRepository.updateProfilePicUrlByKeycloakId(profilePicUrl, userId);
    }
}
