package com.calendar.users.infrastructure.persistence.adapters;

import java.time.LocalDate;

import com.calendar.users.infrastructure.messaging.models.UserBirthdayEventDTO;

import com.calendar.users.domain.models.Registration;

import static org.mockito.ArgumentMatchers.any;

import com.calendar.users.infrastructure.persistence.models.UserAboutRow;
import com.calendar.users.infrastructure.persistence.models.UserPreferencesRow;
import com.calendar.users.domain.models.Theme;
import com.calendar.users.domain.models.UserPreferences;

import com.calendar.users.domain.models.UserAbout;

import com.calendar.users.domain.models.BusinessUser;
import com.calendar.users.exception.BusinessErrorCode;
import com.calendar.users.exception.BusinessException;
import com.calendar.users.infrastructure.messaging.mappers.KafkaDataMapper;
import com.calendar.users.infrastructure.messaging.models.OutboxEventType;
import com.calendar.users.infrastructure.messaging.models.UserCreatedEventDTO;
import com.calendar.users.infrastructure.messaging.models.UserModifiedEventDTO;
import com.calendar.users.infrastructure.persistence.models.IdentityToCheckRow;
import com.calendar.users.infrastructure.persistence.models.UserPictureRow;
import com.calendar.users.domain.models.UserPicture;
import java.util.List;
import com.calendar.users.domain.models.IdentityToCheck;
import reactor.core.publisher.Flux;
import com.calendar.users.domain.models.AccountErasure;
import com.calendar.users.domain.models.AccountState;
import com.calendar.users.domain.models.DeletedUser;
import com.calendar.users.infrastructure.messaging.models.UserDeletedEventDTO;
import com.calendar.users.infrastructure.persistence.mappers.AccountDeletionEntityMapper;
import com.calendar.users.infrastructure.persistence.mappers.AccountDeletionEntityMapperImpl;
import com.calendar.users.infrastructure.persistence.mappers.UserEntityMapper;
import com.calendar.users.infrastructure.persistence.models.DeletedUserRow;
import com.calendar.users.infrastructure.persistence.models.DeletionLockRow;
import com.calendar.users.infrastructure.persistence.repositories.AccountDeletionR2dbcRepository;
import com.calendar.users.infrastructure.persistence.repositories.RetiredHandleR2dbcRepository;
import com.calendar.users.infrastructure.persistence.models.entities.UserEntity;
import com.calendar.users.infrastructure.persistence.repositories.UserR2dbcRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Unexpected database failures are mapped by InfrastructureErrorAspect, tested on its own. */
@ExtendWith(MockitoExtension.class)
class R2dbcUserRepositoryAdapterTest {

        @Mock
        private UserR2dbcRepository userR2dbcRepository;

        @Mock
        private UserEntityMapper userEntityMapper;

        @Mock
        private R2dbcOutboxEventStoreAdapter outboxEventStore;

        @Mock
        private KafkaDataMapper kafkaDataMapper;

        @Mock
        private RetiredHandleR2dbcRepository retiredHandles;

        @Mock
        private AccountDeletionR2dbcRepository accountDeletions;

        private final AccountDeletionEntityMapper accountDeletionMapper = new AccountDeletionEntityMapperImpl();

        // A real mapper: the stored JSON is what the relay sends to Kafka.
        private final ObjectMapper objectMapper = JsonMapper.builder().build();

        private R2dbcUserRepositoryAdapter adapter;

        @BeforeEach
        void setUp() {
                adapter = new R2dbcUserRepositoryAdapter(userR2dbcRepository, userEntityMapper,
                                outboxEventStore, kafkaDataMapper, objectMapper, retiredHandles, accountDeletions,
                                accountDeletionMapper);
        }

        @Test
        void save_ShouldReturnBusinessUser_WhenSuccess() {
                // Given
                UUID id = UUID.randomUUID();
                BusinessUser user = new BusinessUser(id, "user", 1, "F", "L", "url", LocalDateTime.now());
                UserEntity entity = mock(UserEntity.class);

                when(userEntityMapper.toUserEntity(user)).thenReturn(entity);
                when(userR2dbcRepository.save(entity)).thenReturn(Mono.just(entity));
                when(userEntityMapper.toBusinessUser(entity)).thenReturn(user);
                when(kafkaDataMapper.toUserCreatedEventDTO(user))
                                .thenReturn(new UserCreatedEventDTO(id, "user", 1, "url"));
                when(outboxEventStore.append(any(UUID.class), anyString(), anyString())).thenReturn(Mono.just(1L));

                // When
                Mono<BusinessUser> result = adapter.save(user, "kc-123");

                // Then
                StepVerifier.create(result)
                                .expectNext(user)
                                .verifyComplete();

                verify(entity).setKeycloakId("kc-123");
                ArgumentCaptor<String> payload = ArgumentCaptor.captor();
                verify(outboxEventStore).append(eq(id), eq(OutboxEventType.USER_CREATED.name()), payload.capture());
                assertThat(objectMapper.readValue(payload.getValue(), UserCreatedEventDTO.class))
                                .isEqualTo(new UserCreatedEventDTO(id, "user", 1, "url"));
        }

        @Test
        void save_ShouldFail_WhenTheEventCannotBeWritten() {
                // Given
                BusinessUser user = new BusinessUser(UUID.randomUUID(), "user", 1, "F", "L", "url",
                                LocalDateTime.now());
                UserEntity entity = new UserEntity();

                when(userEntityMapper.toUserEntity(user)).thenReturn(entity);
                when(userR2dbcRepository.save(entity)).thenReturn(Mono.just(entity));
                when(userEntityMapper.toBusinessUser(entity)).thenReturn(user);
                when(kafkaDataMapper.toUserCreatedEventDTO(user))
                                .thenReturn(new UserCreatedEventDTO(user.id(), "user", 1, "url"));
                when(outboxEventStore.append(any(UUID.class), anyString(), anyString()))
                                .thenReturn(Mono.error(new IllegalStateException("outbox down")));

                // When / Then: the error reaches the caller, so the transaction rolls back
                StepVerifier.create(adapter.save(user, "kc-123"))
                                .expectError(IllegalStateException.class)
                                .verify();
        }

        @Test
        void save_ShouldMapDataIntegrityViolation() {
                // Given
                BusinessUser user = new BusinessUser(UUID.randomUUID(), "user", 1, "F", "L", "url",
                                LocalDateTime.now());
                UserEntity entity = new UserEntity();

                when(userEntityMapper.toUserEntity(user)).thenReturn(entity);
                when(userR2dbcRepository.save(entity))
                                .thenReturn(Mono.error(new DataIntegrityViolationException("Conflict")));

                // When
                Mono<BusinessUser> result = adapter.save(user, "kc-123");

                // Then
                StepVerifier.create(result)
                                .expectErrorMatches(throwable -> throwable instanceof BusinessException &&
                                                ((BusinessException) throwable)
                                                                .getErrorCode() == BusinessErrorCode.USER_ALREADY_EXISTS)
                                .verify();

                verify(outboxEventStore, never()).append(any(), anyString(), anyString());
        }


        @Test
        void findIdByKeycloakId_ShouldReturnId() {
                // Given
                UUID id = UUID.randomUUID();
                when(userR2dbcRepository.findIdByKeycloakId("kc-123")).thenReturn(Mono.just(id));

                // When
                Mono<UUID> result = adapter.findIdByKeycloakId("kc-123");

                // Then
                StepVerifier.create(result)
                                .expectNext(id)
                                .verifyComplete();
        }


        @Test
        void existsByUserNameAndHashtag_ShouldReturnBoolean() {
                // Given
                when(userR2dbcRepository.existsByUserNameAndHashtag("user", 1234)).thenReturn(Mono.just(true));

                // When
                Mono<Boolean> result = adapter.existsByUserNameAndHashtag("user", 1234);

                // Then
                StepVerifier.create(result)
                                .expectNext(true)
                                .verifyComplete();
        }


        @Test
        void getBusinessUserByUserId_ShouldReturnUser() {
                // Given
                UUID id = UUID.randomUUID();
                UserEntity entity = new UserEntity();
                BusinessUser user = new BusinessUser(id, "user", 1, "F", "L", "url", LocalDateTime.now());

                when(userR2dbcRepository.findLiveById(id)).thenReturn(Mono.just(entity));
                when(userEntityMapper.toBusinessUser(entity)).thenReturn(user);

                // When
                Mono<BusinessUser> result = adapter.getBusinessUserByUserId(id);

                // Then
                StepVerifier.create(result)
                                .expectNext(user)
                                .verifyComplete();
        }



        @Test
        void findIdentitiesToCheck_ShouldMapTheRows() {
                UUID id = UUID.randomUUID();
                LocalDateTime cutoff = LocalDateTime.now();
                when(userR2dbcRepository.findIdentitiesToCheck(cutoff, 10))
                                .thenReturn(Flux.just(new IdentityToCheckRow(id, "kc-1")));

                StepVerifier.create(adapter.findIdentitiesToCheck(cutoff, 10))
                                .expectNext(new IdentityToCheck(id, "kc-1"))
                                .verifyComplete();
        }

        @Test
        void markIdentityChecked_ShouldUpdateTheRow() {
                UUID id = UUID.randomUUID();
                when(userR2dbcRepository.markIdentityChecked(id)).thenReturn(Mono.just(1L));

                StepVerifier.create(adapter.markIdentityChecked(id)).verifyComplete();

                verify(userR2dbcRepository).markIdentityChecked(id);
        }

        @Test
        void deleteOrphan_shouldDropTheOrphansOtherEventsThenWriteUserDeletedWithDeletedAt() {
                UUID id = UUID.randomUUID();
                Instant before = Instant.now().truncatedTo(ChronoUnit.MICROS);
                when(userR2dbcRepository.deleteLiveById(id)).thenReturn(Mono.just(1L));
                when(outboxEventStore.deleteOtherEventsOf(id, "USER_DELETED")).thenReturn(Mono.just(2L));
                when(outboxEventStore.append(any(UUID.class), anyString(), anyString())).thenReturn(Mono.just(1L));

                StepVerifier.create(adapter.deleteOrphan(id)).verifyComplete();

                InOrder order = inOrder(userR2dbcRepository, outboxEventStore);
                order.verify(userR2dbcRepository).deleteLiveById(id);
                order.verify(outboxEventStore).deleteOtherEventsOf(id, "USER_DELETED");
                ArgumentCaptor<String> payload = ArgumentCaptor.captor();
                order.verify(outboxEventStore).append(eq(id), eq(OutboxEventType.USER_DELETED.name()), payload.capture());
                UserDeletedEventDTO event = objectMapper.readValue(payload.getValue(), UserDeletedEventDTO.class);
                assertThat(event.userId()).isEqualTo(id);
                assertThat(event.deletedAt()).isBetween(before, Instant.now());
        }

        @Test
        void deleteOrphan_shouldWriteNothing_whenTheRowIsATombstone() {
                UUID id = UUID.randomUUID();
                when(userR2dbcRepository.deleteLiveById(id)).thenReturn(Mono.just(0L));

                StepVerifier.create(adapter.deleteOrphan(id)).verifyComplete();

                verify(outboxEventStore, never()).deleteOtherEventsOf(any(), anyString());
                verify(outboxEventStore, never()).append(any(), anyString(), anyString());
        }

        @Test
        void deleteOrphan_ShouldFail_WhenTheEventCannotBeWritten() {
                // The error reaches the caller, so the transaction rolls the deletion back.
                UUID id = UUID.randomUUID();
                when(userR2dbcRepository.deleteLiveById(id)).thenReturn(Mono.just(1L));
                when(outboxEventStore.deleteOtherEventsOf(id, "USER_DELETED")).thenReturn(Mono.just(0L));
                when(outboxEventStore.append(any(UUID.class), anyString(), anyString()))
                                .thenReturn(Mono.error(new IllegalStateException("outbox down")));

                StepVerifier.create(adapter.deleteOrphan(id))
                                .expectError(IllegalStateException.class)
                                .verify();
        }

        // --- updateProfile ---------------------------------------------------

        private static final UUID ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
        private static final BusinessUser RENAMED =
                        new BusinessUser(ID, "camille_d", 4321, "Cam", "Dupont", "pic.jpg", LocalDateTime.of(2026, 9, 1, 10, 0));

        @Test
        void updateProfile_ShouldUpdateTheNamesThenWriteUserModifiedWithTheWholeProfile() throws Exception {
                UserEntity stored = mock(UserEntity.class);
                UserModifiedEventDTO event = new UserModifiedEventDTO(ID, "camille_d", 4321, "Cam", "Dupont", "pic.jpg");
                when(userR2dbcRepository.updateProfile(ID, "camille_d", "Cam", "Dupont")).thenReturn(Mono.just(1L));
                when(retiredHandles.isRetired("camille_d", 4321)).thenReturn(Mono.just(false));
                when(userR2dbcRepository.findLiveById(ID)).thenReturn(Mono.just(stored));
                when(userEntityMapper.toBusinessUser(stored)).thenReturn(RENAMED);
                when(kafkaDataMapper.toUserModifiedEventDTO(RENAMED)).thenReturn(event);
                when(outboxEventStore.append(any(UUID.class), anyString(), anyString())).thenReturn(Mono.just(1L));

                StepVerifier.create(adapter.updateProfile(RENAMED, "camille")).expectNext(RENAMED).verifyComplete();

                ArgumentCaptor<String> payload = ArgumentCaptor.captor();
                verify(outboxEventStore).append(eq(ID), eq(OutboxEventType.USER_MODIFIED.name()), payload.capture());
                assertThat(objectMapper.readValue(payload.getValue(), UserModifiedEventDTO.class)).isEqualTo(event);
        }

        @Test
        void updateProfile_ShouldMapAUniqueViolationToUsernameTaken() {
                when(userR2dbcRepository.updateProfile(ID, "camille_d", "Cam", "Dupont"))
                                .thenReturn(Mono.error(new DataIntegrityViolationException("unique_user_identity")));

                StepVerifier.create(adapter.updateProfile(RENAMED, "camille"))
                                .expectErrorMatches(error -> error instanceof BusinessException business
                                                && business.getErrorCode() == BusinessErrorCode.USERNAME_TAKEN)
                                .verify();

                verify(outboxEventStore, never()).append(any(), anyString(), anyString());
        }

        @Test
        void updateProfile_ShouldFailAsNotFound_WhenNoRowWasUpdated() {
                when(userR2dbcRepository.updateProfile(ID, "camille_d", "Cam", "Dupont")).thenReturn(Mono.just(0L));

                StepVerifier.create(adapter.updateProfile(RENAMED, "camille"))
                                .expectErrorMatches(error -> error instanceof BusinessException business
                                                && business.getErrorCode() == BusinessErrorCode.USER_NOT_FOUND)
                                .verify();
        }

        @Test
        void updateProfile_ShouldFail_WhenTheEventCannotBeWritten() {
                // The error reaches the caller, so the transaction rolls the update back.
                UserEntity stored = mock(UserEntity.class);
                when(userR2dbcRepository.updateProfile(ID, "camille_d", "Cam", "Dupont")).thenReturn(Mono.just(1L));
                when(retiredHandles.isRetired("camille_d", 4321)).thenReturn(Mono.just(false));
                when(userR2dbcRepository.findLiveById(ID)).thenReturn(Mono.just(stored));
                when(userEntityMapper.toBusinessUser(stored)).thenReturn(RENAMED);
                when(outboxEventStore.append(any(UUID.class), anyString(), anyString()))
                                .thenReturn(Mono.error(new IllegalStateException("outbox down")));

                StepVerifier.create(adapter.updateProfile(RENAMED, "camille")).expectError(IllegalStateException.class).verify();
        }

        @Test
        void findKeycloakIdByUserId_ShouldReadTheColumn() {
                when(userR2dbcRepository.findKeycloakIdById(ID)).thenReturn(Mono.just("kc-camille"));

                StepVerifier.create(adapter.findKeycloakIdByUserId(ID)).expectNext("kc-camille").verifyComplete();
        }

        // --- pictures --------------------------------------------------------

        @Test
        void updatePicture_ShouldPointTheUserAtTheKeyThenWriteUserModified() {
                UserEntity stored = mock(UserEntity.class);
                when(userR2dbcRepository.updatePicture(ID, "avatars/k.jpg")).thenReturn(Mono.just(1L));
                when(userR2dbcRepository.findLiveById(ID)).thenReturn(Mono.just(stored));
                when(userEntityMapper.toBusinessUser(stored)).thenReturn(RENAMED);
                when(kafkaDataMapper.toUserModifiedEventDTO(RENAMED))
                                .thenReturn(new UserModifiedEventDTO(ID, "camille_d", 4321, "Cam", "Dupont", "avatars/k.jpg"));
                when(outboxEventStore.append(any(UUID.class), anyString(), anyString())).thenReturn(Mono.just(1L));

                StepVerifier.create(adapter.updatePicture(ID, "avatars/k.jpg")).expectNext(RENAMED).verifyComplete();

                verify(outboxEventStore).append(eq(ID), eq(OutboxEventType.USER_MODIFIED.name()), anyString());
        }

        @Test
        void updatePicture_ShouldFailAsNotFound_WhenNoRowWasUpdated() {
                when(userR2dbcRepository.updatePicture(ID, "avatars/k.jpg")).thenReturn(Mono.just(0L));

                StepVerifier.create(adapter.updatePicture(ID, "avatars/k.jpg"))
                                .expectErrorMatches(error -> error instanceof BusinessException business
                                                && business.getErrorCode() == BusinessErrorCode.USER_NOT_FOUND)
                                .verify();
        }

        @Test
        void findPictures_ShouldMapTheRows() {
                when(userR2dbcRepository.findPictures(List.of(ID)))
                                .thenReturn(Flux.just(new UserPictureRow(ID, "avatars/k.jpg")));

                StepVerifier.create(adapter.findPictures(List.of(ID)))
                                .expectNext(new UserPicture(ID, "avatars/k.jpg"))
                                .verifyComplete();
        }

        @Test
        void findPictures_ShouldNotQuery_ForNoUsers() {
                StepVerifier.create(adapter.findPictures(List.of())).verifyComplete();

                verify(userR2dbcRepository, never()).findPictures(any());
        }

        @Test
        void findAbout_shouldReadTheTextArrayAsAList() {
                UUID userId = UUID.randomUUID();
                when(userR2dbcRepository.findAboutById(userId))
                                .thenReturn(Mono.just(new UserAboutRow("Partante", new String[] {"Rando", "Ciné"})));

                StepVerifier.create(adapter.findAbout(userId))
                                .expectNext(new UserAbout("Partante", List.of("Rando", "Ciné")))
                                .verifyComplete();
        }

        @Test
        void findAbout_shouldReadANullArrayAsNoInterest() {
                UUID userId = UUID.randomUUID();
                when(userR2dbcRepository.findAboutById(userId)).thenReturn(Mono.just(new UserAboutRow(null, null)));

                StepVerifier.create(adapter.findAbout(userId)).expectNext(UserAbout.EMPTY).verifyComplete();
        }

        @Test
        void updateAbout_shouldWriteTheInterestsAsAnArray() {
                UUID userId = UUID.randomUUID();
                UserAbout about = new UserAbout("Partante", List.of("Rando"));
                when(userR2dbcRepository.updateAbout(userId, "Partante", new String[] {"Rando"})).thenReturn(Mono.just(1L));

                StepVerifier.create(adapter.updateAbout(userId, about)).expectNext(about).verifyComplete();
        }

        @Test
        void updateAbout_shouldReportAnUnknownUser() {
                UUID userId = UUID.randomUUID();
                when(userR2dbcRepository.updateAbout(any(), any(), any())).thenReturn(Mono.just(0L));

                StepVerifier.create(adapter.updateAbout(userId, UserAbout.EMPTY))
                                .expectErrorMatches(error -> error instanceof BusinessException exception
                                                && exception.getErrorCode() == BusinessErrorCode.USER_NOT_FOUND)
                                .verify();
        }

        @Test
        void findPreferences_shouldReadTheStoredTheme() {
                UUID userId = UUID.randomUUID();
                when(userR2dbcRepository.findPreferencesById(userId)).thenReturn(Mono.just(new UserPreferencesRow("DARK")));

                StepVerifier.create(adapter.findPreferences(userId))
                                .expectNext(new UserPreferences(Theme.DARK))
                                .verifyComplete();
        }

        @Test
        void findPreferences_shouldReadANullThemeAsTheDevices() {
                UUID userId = UUID.randomUUID();
                when(userR2dbcRepository.findPreferencesById(userId)).thenReturn(Mono.just(new UserPreferencesRow(null)));

                StepVerifier.create(adapter.findPreferences(userId)).expectNext(UserPreferences.DEFAULT).verifyComplete();
        }

        @Test
        void findPreferences_shouldBeEmpty_forAnUnknownUser() {
                UUID userId = UUID.randomUUID();
                when(userR2dbcRepository.findPreferencesById(userId)).thenReturn(Mono.empty());

                StepVerifier.create(adapter.findPreferences(userId)).verifyComplete();
        }

        @Test
        void updatePreferences_shouldWriteTheThemesName() {
                UUID userId = UUID.randomUUID();
                UserPreferences light = new UserPreferences(Theme.LIGHT);
                when(userR2dbcRepository.updatePreferences(userId, "LIGHT")).thenReturn(Mono.just(1L));

                StepVerifier.create(adapter.updatePreferences(userId, light)).expectNext(light).verifyComplete();
        }

        @Test
        void updatePreferences_shouldReportAnUnknownUser() {
                UUID userId = UUID.randomUUID();
                when(userR2dbcRepository.updatePreferences(any(), any())).thenReturn(Mono.just(0L));

                StepVerifier.create(adapter.updatePreferences(userId, UserPreferences.DEFAULT))
                                .expectErrorMatches(error -> error instanceof BusinessException exception
                                                && exception.getErrorCode() == BusinessErrorCode.USER_NOT_FOUND)
                                .verify();
        }

        // --- retired handles on rename -----------------------------------------

        @Test
        void updateProfile_shouldFailAsUsernameTaken_whenTheNewHandleIsRetired() {
                when(userR2dbcRepository.updateProfile(ID, "camille_d", "Cam", "Dupont")).thenReturn(Mono.just(1L));
                when(retiredHandles.isRetired("camille_d", 4321)).thenReturn(Mono.just(true));

                StepVerifier.create(adapter.updateProfile(RENAMED, "camille"))
                                .expectErrorMatches(error -> error instanceof BusinessException business
                                                && business.getErrorCode() == BusinessErrorCode.USERNAME_TAKEN)
                                .verify();

                verify(outboxEventStore, never()).append(any(), anyString(), anyString());
        }

        @Test
        void updateProfile_shouldNotCheckRetirement_whenTheHandleIsUnchanged() {
                // A legacy row differing only by case from a deleted user's handle must keep editing its names.
                UserEntity stored = mock(UserEntity.class);
                when(userR2dbcRepository.updateProfile(ID, "camille_d", "Cam", "Dupont")).thenReturn(Mono.just(1L));
                when(userR2dbcRepository.findLiveById(ID)).thenReturn(Mono.just(stored));
                when(userEntityMapper.toBusinessUser(stored)).thenReturn(RENAMED);
                when(outboxEventStore.append(any(UUID.class), anyString(), anyString())).thenReturn(Mono.just(1L));

                StepVerifier.create(adapter.updateProfile(RENAMED, "Camille_D")).expectNext(RENAMED).verifyComplete();

                verifyNoInteractions(retiredHandles);
        }

        // --- eraseAccount --------------------------------------------------------

        private static final Instant DELETED_AT = Instant.parse("2026-10-15T14:03:27.123456Z");
        private static final OffsetDateTime DELETED_AT_UTC = OffsetDateTime.ofInstant(DELETED_AT, ZoneOffset.UTC);

        private void givenALiveRowLocked() {
                when(userR2dbcRepository.lockForDeletion(ID)).thenReturn(Mono.just(new DeletionLockRow(ID, "kc-camille", null)));
        }

        private void givenEveryStepSucceeds() {
                givenEveryStepBeforeTheEventSucceeds();
                when(outboxEventStore.append(any(UUID.class), anyString(), anyString())).thenReturn(Mono.just(9L));
        }

        private void givenEveryStepBeforeTheEventSucceeds() {
                when(retiredHandles.retireHandleOf(ID)).thenReturn(Mono.just(1L));
                when(userR2dbcRepository.tombstone(ID, DELETED_AT_UTC)).thenReturn(Mono.just(1L));
                when(accountDeletions.insertJob(ID, "kc-camille", DELETED_AT_UTC)).thenReturn(Mono.just(1L));
                when(outboxEventStore.deleteOtherEventsOf(ID, "USER_DELETED")).thenReturn(Mono.just(3L));
        }

        @Test
        void eraseAccount_shouldReturnUnknownUser_whenNoRowIsLocked() {
                when(userR2dbcRepository.lockForDeletion(ID)).thenReturn(Mono.empty());

                StepVerifier.create(adapter.eraseAccount(ID, DELETED_AT)).expectNext(AccountErasure.UNKNOWN_USER).verifyComplete();

                verifyNoInteractions(retiredHandles, accountDeletions, outboxEventStore);
        }

        @Test
        void eraseAccount_shouldReturnAlreadyErasedWithoutWriting_whenTheRowIsATombstone() {
                when(userR2dbcRepository.lockForDeletion(ID))
                                .thenReturn(Mono.just(new DeletionLockRow(ID, "deleted:" + ID, DELETED_AT_UTC)));

                StepVerifier.create(adapter.eraseAccount(ID, DELETED_AT.plusSeconds(60)))
                                .expectNext(AccountErasure.ALREADY_ERASED)
                                .verifyComplete();

                verify(userR2dbcRepository, never()).tombstone(any(), any());
                verifyNoInteractions(retiredHandles, accountDeletions, outboxEventStore);
        }

        @Test
        void eraseAccount_shouldRetireTombstoneQueueDropAndAppendInThatOrder_whenTheRowIsLive() {
                givenALiveRowLocked();
                givenEveryStepSucceeds();

                StepVerifier.create(adapter.eraseAccount(ID, DELETED_AT)).expectNext(AccountErasure.ERASED).verifyComplete();

                InOrder order = inOrder(userR2dbcRepository, retiredHandles, accountDeletions, outboxEventStore);
                order.verify(userR2dbcRepository).lockForDeletion(ID);
                order.verify(retiredHandles).retireHandleOf(ID);
                order.verify(userR2dbcRepository).tombstone(ID, DELETED_AT_UTC);
                order.verify(accountDeletions).insertJob(ID, "kc-camille", DELETED_AT_UTC);
                order.verify(outboxEventStore).deleteOtherEventsOf(ID, "USER_DELETED");
                order.verify(outboxEventStore).append(eq(ID), eq("USER_DELETED"), anyString());
        }

        @Test
        void eraseAccount_shouldCarryDeletedAtInTheEvent_whenErasing() {
                givenALiveRowLocked();
                givenEveryStepSucceeds();

                adapter.eraseAccount(ID, DELETED_AT).block();

                ArgumentCaptor<String> payload = ArgumentCaptor.captor();
                verify(outboxEventStore).append(eq(ID), eq("USER_DELETED"), payload.capture());
                assertThat(payload.getValue())
                                .isEqualTo("{\"userId\":\"" + ID + "\",\"deletedAt\":\"2026-10-15T14:03:27.123456Z\"}");
        }

        @Test
        void eraseAccount_shouldFail_whenTheTombstoneMatchesNoRow() {
                givenALiveRowLocked();
                when(retiredHandles.retireHandleOf(ID)).thenReturn(Mono.just(1L));
                when(userR2dbcRepository.tombstone(ID, DELETED_AT_UTC)).thenReturn(Mono.just(0L));

                StepVerifier.create(adapter.eraseAccount(ID, DELETED_AT)).expectError(IllegalStateException.class).verify();

                verifyNoInteractions(accountDeletions, outboxEventStore);
        }

        @Test
        void eraseAccount_shouldFail_whenTheEventCannotBeWritten() {
                // The error reaches the caller, so the transaction rolls every step back.
                givenALiveRowLocked();
                givenEveryStepBeforeTheEventSucceeds();
                when(outboxEventStore.append(any(UUID.class), anyString(), anyString()))
                                .thenReturn(Mono.error(new IllegalStateException("outbox down")));

                StepVerifier.create(adapter.eraseAccount(ID, DELETED_AT)).expectError(IllegalStateException.class).verify();
        }

        // --- tombstone reads -----------------------------------------------------

        @Test
        void findAccountState_shouldMapTheFlag_whenTheRowExists() {
                UUID live = UUID.randomUUID();
                when(userR2dbcRepository.isDeleted(ID)).thenReturn(Mono.just(true));
                when(userR2dbcRepository.isDeleted(live)).thenReturn(Mono.just(false));

                StepVerifier.create(adapter.findAccountState(ID)).expectNext(AccountState.DELETED).verifyComplete();
                StepVerifier.create(adapter.findAccountState(live)).expectNext(AccountState.ACTIVE).verifyComplete();
        }

        @Test
        void findAccountState_shouldBeEmpty_whenTheUserIsUnknown() {
                when(userR2dbcRepository.isDeleted(ID)).thenReturn(Mono.empty());

                StepVerifier.create(adapter.findAccountState(ID)).verifyComplete();
        }

        @Test
        void findDeletedIds_shouldNotQuery_whenNoIdIsGiven() {
                StepVerifier.create(adapter.findDeletedIds(List.of())).verifyComplete();

                verify(userR2dbcRepository, never()).findDeletedIds(any());
        }

        @Test
        void findDeletedIds_shouldReturnTheTombstones_whenIdsAreGiven() {
                when(userR2dbcRepository.findDeletedIds(List.of(ID))).thenReturn(Flux.just(ID));

                StepVerifier.create(adapter.findDeletedIds(List.of(ID))).expectNext(ID).verifyComplete();
        }

        @Test
        void findDeleted_shouldUseTheFirstPageQuery_whenNoCursorIsGiven() {
                when(userR2dbcRepository.findDeletedFirst(10)).thenReturn(Flux.just(new DeletedUserRow(ID, DELETED_AT_UTC)));

                StepVerifier.create(adapter.findDeleted(null, 10))
                                .expectNext(new DeletedUser(ID, DELETED_AT))
                                .verifyComplete();
        }

        @Test
        void findDeleted_shouldUseTheKeysetQuery_whenACursorIsGiven() {
                UUID next = UUID.randomUUID();
                when(userR2dbcRepository.findDeletedAfter(DELETED_AT_UTC, ID, 10))
                                .thenReturn(Flux.just(new DeletedUserRow(next, DELETED_AT_UTC.plusSeconds(1))));

                StepVerifier.create(adapter.findDeleted(new DeletedUser(ID, DELETED_AT), 10))
                                .expectNext(new DeletedUser(next, DELETED_AT.plusSeconds(1)))
                                .verifyComplete();
        }

        // --- birthday --------------------------------------------------------

        private static final LocalDate BIRTH_DATE = LocalDate.of(1995, 3, 12);
        private static final BusinessUser REGISTERED = new BusinessUser(ID, "camille_d", 4321, "Cam", "Dupont",
                        "pic.jpg", LocalDateTime.of(2026, 9, 1, 10, 0), BIRTH_DATE, true);
        private static final UserBirthdayEventDTO BIRTHDAY_EVENT = new UserBirthdayEventDTO(ID, "Cam", 3, 12);

        @Test
        void updateProfile_shouldAlsoWriteUserBirthday_whenTheUserHasABirthDate() {
                UserEntity stored = mock(UserEntity.class);
                when(userR2dbcRepository.updateProfile(ID, "camille_d", "Cam", "Dupont")).thenReturn(Mono.just(1L));
                when(retiredHandles.isRetired("camille_d", 4321)).thenReturn(Mono.just(false));
                when(userR2dbcRepository.findLiveById(ID)).thenReturn(Mono.just(stored));
                when(userEntityMapper.toBusinessUser(stored)).thenReturn(REGISTERED);
                when(kafkaDataMapper.toUserModifiedEventDTO(REGISTERED))
                                .thenReturn(new UserModifiedEventDTO(ID, "camille_d", 4321, "Cam", "Dupont", "pic.jpg"));
                when(kafkaDataMapper.toUserBirthdayEventDTO(REGISTERED)).thenReturn(BIRTHDAY_EVENT);
                when(outboxEventStore.append(any(UUID.class), anyString(), anyString())).thenReturn(Mono.just(1L));

                StepVerifier.create(adapter.updateProfile(REGISTERED, "camille")).expectNext(REGISTERED).verifyComplete();

                InOrder order = inOrder(outboxEventStore);
                order.verify(outboxEventStore).append(eq(ID), eq(OutboxEventType.USER_MODIFIED.name()), anyString());
                order.verify(outboxEventStore).append(eq(ID), eq(OutboxEventType.USER_BIRTHDAY.name()), anyString());
        }

        @Test
        void updateProfile_shouldNotWriteUserBirthday_whenTheUserHasNoBirthDate() {
                UserEntity stored = mock(UserEntity.class);
                when(userR2dbcRepository.updateProfile(ID, "camille_d", "Cam", "Dupont")).thenReturn(Mono.just(1L));
                when(retiredHandles.isRetired("camille_d", 4321)).thenReturn(Mono.just(false));
                when(userR2dbcRepository.findLiveById(ID)).thenReturn(Mono.just(stored));
                when(userEntityMapper.toBusinessUser(stored)).thenReturn(RENAMED);
                when(outboxEventStore.append(any(UUID.class), anyString(), anyString())).thenReturn(Mono.just(1L));

                StepVerifier.create(adapter.updateProfile(RENAMED, "camille")).expectNext(RENAMED).verifyComplete();

                verify(outboxEventStore, never()).append(any(), eq(OutboxEventType.USER_BIRTHDAY.name()), anyString());
        }

        @Test
        void completeRegistration_shouldStoreTheBirthDateThenWriteUserBirthday() throws Exception {
                UserEntity stored = mock(UserEntity.class);
                when(userR2dbcRepository.completeRegistration(ID, BIRTH_DATE)).thenReturn(Mono.just(1L));
                when(userR2dbcRepository.findLiveById(ID)).thenReturn(Mono.just(stored));
                when(userEntityMapper.toBusinessUser(stored)).thenReturn(REGISTERED);
                when(kafkaDataMapper.toUserBirthdayEventDTO(REGISTERED)).thenReturn(BIRTHDAY_EVENT);
                when(outboxEventStore.append(any(UUID.class), anyString(), anyString())).thenReturn(Mono.just(1L));

                StepVerifier.create(adapter.completeRegistration(ID, new Registration(BIRTH_DATE)))
                                .expectNext(REGISTERED)
                                .verifyComplete();

                ArgumentCaptor<String> payload = ArgumentCaptor.captor();
                verify(outboxEventStore).append(eq(ID), eq(OutboxEventType.USER_BIRTHDAY.name()), payload.capture());
                assertThat(objectMapper.readTree(payload.getValue()).toString())
                                .isEqualTo("{\"userId\":\"" + ID + "\",\"displayName\":\"Cam\",\"month\":3,\"day\":12}");
        }

        @Test
        void completeRegistration_shouldFailAsNotFound_whenNoRowWasUpdated() {
                when(userR2dbcRepository.completeRegistration(ID, BIRTH_DATE)).thenReturn(Mono.just(0L));

                StepVerifier.create(adapter.completeRegistration(ID, new Registration(BIRTH_DATE)))
                                .expectErrorMatches(error -> error instanceof BusinessException business
                                                && business.getErrorCode() == BusinessErrorCode.USER_NOT_FOUND)
                                .verify();

                verify(outboxEventStore, never()).append(any(), anyString(), anyString());
        }

        @Test
        void completeRegistration_shouldFail_whenTheEventCannotBeWritten() {
                // The error reaches the caller, so the transaction rolls the birth date back.
                UserEntity stored = mock(UserEntity.class);
                when(userR2dbcRepository.completeRegistration(ID, BIRTH_DATE)).thenReturn(Mono.just(1L));
                when(userR2dbcRepository.findLiveById(ID)).thenReturn(Mono.just(stored));
                when(userEntityMapper.toBusinessUser(stored)).thenReturn(REGISTERED);
                when(kafkaDataMapper.toUserBirthdayEventDTO(REGISTERED)).thenReturn(BIRTHDAY_EVENT);
                when(outboxEventStore.append(any(UUID.class), anyString(), anyString()))
                                .thenReturn(Mono.error(new IllegalStateException("outbox down")));

                StepVerifier.create(adapter.completeRegistration(ID, new Registration(BIRTH_DATE)))
                                .expectError(IllegalStateException.class)
                                .verify();
        }
}
