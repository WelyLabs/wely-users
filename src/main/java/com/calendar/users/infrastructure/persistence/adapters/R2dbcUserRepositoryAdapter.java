package com.calendar.users.infrastructure.persistence.adapters;

import com.calendar.users.domain.models.BusinessUser;
import com.calendar.users.domain.models.IdentityToCheck;
import com.calendar.users.domain.ports.UserRepository;
import com.calendar.users.exception.BusinessErrorCode;
import com.calendar.users.exception.BusinessException;
import com.calendar.users.infrastructure.messaging.mappers.KafkaDataMapper;
import com.calendar.users.infrastructure.messaging.models.OutboxEventType;
import com.calendar.users.infrastructure.messaging.models.UserDeletedEventDTO;
import com.calendar.users.infrastructure.persistence.mappers.UserEntityMapper;
import com.calendar.users.infrastructure.persistence.models.entities.UserEntity;
import com.calendar.users.infrastructure.persistence.repositories.UserR2dbcRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.UUID;

/** Persists users through R2DBC. Unexpected failures are mapped by {@code InfrastructureErrorAspect}. */
@Component
public class R2dbcUserRepositoryAdapter implements UserRepository {

    private final UserR2dbcRepository userR2dbcRepository;
    private final UserEntityMapper userEntityMapper;
    private final R2dbcOutboxEventStoreAdapter outboxEventStore;
    private final KafkaDataMapper kafkaDataMapper;
    private final ObjectMapper objectMapper;

    public R2dbcUserRepositoryAdapter(UserR2dbcRepository userR2dbcRepository,
                                      UserEntityMapper userEntityMapper,
                                      R2dbcOutboxEventStoreAdapter outboxEventStore,
                                      KafkaDataMapper kafkaDataMapper,
                                      ObjectMapper objectMapper) {
        this.userR2dbcRepository = userR2dbcRepository;
        this.userEntityMapper = userEntityMapper;
        this.outboxEventStore = outboxEventStore;
        this.kafkaDataMapper = kafkaDataMapper;
        this.objectMapper = objectMapper;
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
                .flatMap(saved -> appendUserCreatedEvent(saved).thenReturn(saved));
    }

    @Override
    public Flux<IdentityToCheck> findIdentitiesToCheck(LocalDateTime createdBefore, int limit) {
        return userR2dbcRepository.findIdentitiesToCheck(createdBefore, limit)
                .map(row -> new IdentityToCheck(row.id(), row.keycloakId()));
    }

    @Override
    public Mono<Void> markIdentityChecked(UUID userId) {
        return userR2dbcRepository.markIdentityChecked(userId).then();
    }

    /** Deletes the user and writes USER_DELETED in one transaction, like save does for USER_CREATED. */
    @Override
    @Transactional
    public Mono<Void> deleteOrphan(UUID userId) {
        return userR2dbcRepository.deleteById(userId)
                .then(appendEvent(userId, OutboxEventType.USER_DELETED, new UserDeletedEventDTO(userId)))
                .then();
    }

    /**
     * Updates the names and writes USER_MODIFIED in one transaction, joined by the caller's when
     * there is one: {@code UserService.updateProfile} also calls Keycloak inside it.
     */
    @Override
    @Transactional
    public Mono<BusinessUser> updateProfile(BusinessUser businessUser) {
        return userR2dbcRepository.updateProfile(businessUser.id(), businessUser.userName(),
                        businessUser.firstName(), businessUser.lastName())
                .onErrorMap(DataIntegrityViolationException.class,
                        e -> new BusinessException(BusinessErrorCode.USERNAME_TAKEN))
                .flatMap(updated -> updated == 0
                        ? Mono.<BusinessUser>error(new BusinessException(BusinessErrorCode.USER_NOT_FOUND))
                        : getBusinessUserByUserId(businessUser.id()))
                .flatMap(saved -> appendEvent(saved.id(), OutboxEventType.USER_MODIFIED,
                        kafkaDataMapper.toUserModifiedEventDTO(saved)).thenReturn(saved));
    }

    @Override
    public Mono<String> findKeycloakIdByUserId(UUID userId) {
        return userR2dbcRepository.findKeycloakIdById(userId);
    }

    /** Writes USER_CREATED to the outbox; {@code OutboxRelay} sends it to Kafka. */
    private Mono<Long> appendUserCreatedEvent(BusinessUser user) {
        return appendEvent(user.id(), OutboxEventType.USER_CREATED, kafkaDataMapper.toUserCreatedEventDTO(user));
    }

    private Mono<Long> appendEvent(UUID userId, OutboxEventType type, Object event) {
        return Mono.fromCallable(() -> objectMapper.writeValueAsString(event))
                .flatMap(payload -> outboxEventStore.append(userId, type.name(), payload));
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
