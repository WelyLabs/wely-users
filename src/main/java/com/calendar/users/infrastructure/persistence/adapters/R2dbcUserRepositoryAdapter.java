package com.calendar.users.infrastructure.persistence.adapters;

import com.calendar.users.domain.models.AccountErasure;
import com.calendar.users.domain.models.AccountState;
import com.calendar.users.domain.models.BusinessUser;
import com.calendar.users.domain.models.DeletedUser;
import com.calendar.users.domain.models.UserAbout;
import com.calendar.users.domain.models.IdentityToCheck;
import com.calendar.users.domain.models.Theme;
import com.calendar.users.domain.models.UserPicture;
import com.calendar.users.domain.models.UserPreferences;
import com.calendar.users.domain.ports.UserRepository;
import com.calendar.users.exception.BusinessErrorCode;
import com.calendar.users.exception.BusinessException;
import com.calendar.users.infrastructure.messaging.mappers.KafkaDataMapper;
import com.calendar.users.infrastructure.messaging.models.OutboxEventType;
import com.calendar.users.infrastructure.messaging.models.UserDeletedEventDTO;
import com.calendar.users.infrastructure.persistence.mappers.AccountDeletionEntityMapper;
import com.calendar.users.infrastructure.persistence.mappers.UserEntityMapper;
import com.calendar.users.infrastructure.persistence.models.DeletedUserRow;
import com.calendar.users.infrastructure.persistence.models.DeletionLockRow;
import com.calendar.users.infrastructure.persistence.models.entities.UserEntity;
import com.calendar.users.infrastructure.persistence.repositories.AccountDeletionR2dbcRepository;
import com.calendar.users.infrastructure.persistence.repositories.RetiredHandleR2dbcRepository;
import com.calendar.users.infrastructure.persistence.repositories.UserR2dbcRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Persists users through R2DBC. Unexpected failures are mapped by {@code InfrastructureErrorAspect}. */
@Component
public class R2dbcUserRepositoryAdapter implements UserRepository {

    private final UserR2dbcRepository userR2dbcRepository;
    private final UserEntityMapper userEntityMapper;
    private final R2dbcOutboxEventStoreAdapter outboxEventStore;
    private final KafkaDataMapper kafkaDataMapper;
    private final ObjectMapper objectMapper;
    private final RetiredHandleR2dbcRepository retiredHandles;
    private final AccountDeletionR2dbcRepository accountDeletions;
    private final AccountDeletionEntityMapper accountDeletionMapper;

    public R2dbcUserRepositoryAdapter(UserR2dbcRepository userR2dbcRepository,
                                      UserEntityMapper userEntityMapper,
                                      R2dbcOutboxEventStoreAdapter outboxEventStore,
                                      KafkaDataMapper kafkaDataMapper,
                                      ObjectMapper objectMapper,
                                      RetiredHandleR2dbcRepository retiredHandles,
                                      AccountDeletionR2dbcRepository accountDeletions,
                                      AccountDeletionEntityMapper accountDeletionMapper) {
        this.userR2dbcRepository = userR2dbcRepository;
        this.userEntityMapper = userEntityMapper;
        this.outboxEventStore = outboxEventStore;
        this.kafkaDataMapper = kafkaDataMapper;
        this.objectMapper = objectMapper;
        this.retiredHandles = retiredHandles;
        this.accountDeletions = accountDeletions;
        this.accountDeletionMapper = accountDeletionMapper;
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

    /** Deletes a live orphan and its other events, and writes USER_DELETED, in one transaction. */
    @Override
    @Transactional
    public Mono<Void> deleteOrphan(UUID userId) {
        return userR2dbcRepository.deleteLiveById(userId)
                .filter(deleted -> deleted > 0)
                .flatMap(deleted -> outboxEventStore.deleteOtherEventsOf(userId, OutboxEventType.USER_DELETED.name())
                        .then(Mono.defer(() -> appendEvent(userId, OutboxEventType.USER_DELETED,
                                new UserDeletedEventDTO(userId, Instant.now().truncatedTo(ChronoUnit.MICROS))))))
                .then();
    }

    /** Updates the names and writes USER_MODIFIED in one transaction; a rename onto a retired handle fails. */
    @Override
    @Transactional
    public Mono<BusinessUser> updateProfile(BusinessUser businessUser, String previousUserName) {
        return userR2dbcRepository.updateProfile(businessUser.id(), businessUser.userName(),
                        businessUser.firstName(), businessUser.lastName())
                .onErrorMap(DataIntegrityViolationException.class,
                        e -> new BusinessException(BusinessErrorCode.USERNAME_TAKEN))
                .flatMap(updated -> updated == 0
                        ? Mono.<BusinessUser>error(new BusinessException(BusinessErrorCode.USER_NOT_FOUND))
                        : refuseRetiredHandle(businessUser, previousUserName)
                                .then(Mono.defer(() -> getBusinessUserByUserId(businessUser.id()))))
                .flatMap(saved -> appendEvent(saved.id(), OutboxEventType.USER_MODIFIED,
                        kafkaDataMapper.toUserModifiedEventDTO(saved)).thenReturn(saved));
    }

    private Mono<Void> refuseRetiredHandle(BusinessUser renamed, String previousUserName) {
        // After the UPDATE on purpose: it waited on the unique index for a concurrent deletion to commit.
        if (sameHandleName(renamed.userName(), previousUserName)) {
            return Mono.empty();
        }
        return retiredHandles.isRetired(renamed.userName(), renamed.hashtag())
                .flatMap(retired -> retired
                        ? Mono.<Void>error(new BusinessException(BusinessErrorCode.USERNAME_TAKEN))
                        : Mono.<Void>empty());
    }

    private static boolean sameHandleName(String userName, String previousUserName) {
        return previousUserName != null
                && userName.toLowerCase(Locale.ROOT).equals(previousUserName.toLowerCase(Locale.ROOT));
    }

    /**
     * Locks the row, retires its handle, writes the tombstone, queues the identity and media cleanup,
     * drops the user's other outbox events and appends USER_DELETED, all in one transaction.
     */
    @Override
    @Transactional
    public Mono<AccountErasure> eraseAccount(UUID userId, Instant deletedAt) {
        return userR2dbcRepository.lockForDeletion(userId)
                .flatMap(row -> row.deletedAt() != null
                        ? Mono.just(AccountErasure.ALREADY_ERASED)
                        : erase(row, deletedAt))
                .defaultIfEmpty(AccountErasure.UNKNOWN_USER);
    }

    private Mono<AccountErasure> erase(DeletionLockRow row, Instant deletedAt) {
        UUID userId = row.id();
        OffsetDateTime at = OffsetDateTime.ofInstant(deletedAt, ZoneOffset.UTC);
        // Each step deferred: built eagerly, a step would be prepared even after the one before failed.
        return retiredHandles.retireHandleOf(userId)
                .then(Mono.defer(() -> userR2dbcRepository.tombstone(userId, at)))
                .flatMap(updated -> updated == 1
                        ? Mono.just(updated)
                        : Mono.error(new IllegalStateException("The tombstone matched " + updated + " rows")))
                .then(Mono.defer(() -> accountDeletions.insertJob(userId, row.keycloakId(), at)))
                .then(Mono.defer(() -> outboxEventStore.deleteOtherEventsOf(userId, OutboxEventType.USER_DELETED.name())))
                .then(Mono.defer(() -> appendEvent(userId, OutboxEventType.USER_DELETED,
                        new UserDeletedEventDTO(userId, deletedAt))))
                .thenReturn(AccountErasure.ERASED);
    }

    @Override
    public Mono<AccountState> findAccountState(UUID userId) {
        return userR2dbcRepository.isDeleted(userId)
                .map(deleted -> deleted ? AccountState.DELETED : AccountState.ACTIVE);
    }

    @Override
    public Flux<UUID> findDeletedIds(Collection<UUID> userIds) {
        if (userIds.isEmpty()) {
            // IN () is not valid SQL.
            return Flux.empty();
        }
        return userR2dbcRepository.findDeletedIds(userIds);
    }

    @Override
    public Flux<DeletedUser> findDeleted(DeletedUser after, int limit) {
        Flux<DeletedUserRow> rows = after == null
                ? userR2dbcRepository.findDeletedFirst(limit)
                : userR2dbcRepository.findDeletedAfter(
                        OffsetDateTime.ofInstant(after.deletedAt(), ZoneOffset.UTC), after.userId(), limit);
        return rows.map(accountDeletionMapper::toDeletedUser);
    }

    /** The new picture and USER_MODIFIED in one transaction, as for a profile update. */
    @Override
    @Transactional
    public Mono<BusinessUser> updatePicture(UUID userId, String reference) {
        return userR2dbcRepository.updatePicture(userId, reference)
                .flatMap(updated -> updated == 0
                        ? Mono.<BusinessUser>error(new BusinessException(BusinessErrorCode.USER_NOT_FOUND))
                        : getBusinessUserByUserId(userId))
                .flatMap(saved -> appendEvent(saved.id(), OutboxEventType.USER_MODIFIED,
                        kafkaDataMapper.toUserModifiedEventDTO(saved)).thenReturn(saved));
    }

    @Override
    public Flux<UserPicture> findPictures(Collection<UUID> userIds) {
        if (userIds.isEmpty()) {
            // IN () is not valid SQL.
            return Flux.empty();
        }
        return userR2dbcRepository.findPictures(userIds)
                .map(row -> new UserPicture(row.id(), row.profilePicUrl()));
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
        return userR2dbcRepository.findLiveById(userId).map(userEntityMapper::toBusinessUser);
    }

    @Override
    public Mono<UserAbout> findAbout(UUID userId) {
        return userR2dbcRepository.findAboutById(userId)
                .map(row -> new UserAbout(row.bio(), row.interests() == null ? List.of() : List.of(row.interests())));
    }

    @Override
    public Mono<UserAbout> updateAbout(UUID userId, UserAbout about) {
        return userR2dbcRepository.updateAbout(userId, about.bio(), about.interests().toArray(String[]::new))
                .flatMap(updated -> updated == 0
                        ? Mono.<UserAbout>error(new BusinessException(BusinessErrorCode.USER_NOT_FOUND))
                        : Mono.just(about));
    }

    @Override
    public Mono<UserPreferences> findPreferences(UUID userId) {
        return userR2dbcRepository.findPreferencesById(userId)
                .map(row -> new UserPreferences(row.theme() == null ? null : Theme.valueOf(row.theme())));
    }

    @Override
    public Mono<UserPreferences> updatePreferences(UUID userId, UserPreferences preferences) {
        return userR2dbcRepository.updatePreferences(userId, preferences.theme().name())
                .flatMap(updated -> updated == 0
                        ? Mono.<UserPreferences>error(new BusinessException(BusinessErrorCode.USER_NOT_FOUND))
                        : Mono.just(preferences));
    }
}
