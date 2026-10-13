package com.calendar.users.infrastructure.persistence.adapters;

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
import com.calendar.users.infrastructure.persistence.mappers.UserEntityMapper;
import com.calendar.users.infrastructure.persistence.models.entities.UserEntity;
import com.calendar.users.infrastructure.persistence.repositories.UserR2dbcRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
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

        // A real mapper: the stored JSON is what the relay sends to Kafka.
        private final ObjectMapper objectMapper = JsonMapper.builder().build();

        private R2dbcUserRepositoryAdapter adapter;

        @BeforeEach
        void setUp() {
                adapter = new R2dbcUserRepositoryAdapter(userR2dbcRepository, userEntityMapper,
                                outboxEventStore, kafkaDataMapper, objectMapper);
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

                when(userR2dbcRepository.findById(id)).thenReturn(Mono.just(entity));
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
        void deleteOrphan_ShouldDeleteTheUserThenWriteUserDeleted() {
                UUID id = UUID.randomUUID();
                when(userR2dbcRepository.deleteById(id)).thenReturn(Mono.empty());
                when(outboxEventStore.append(any(UUID.class), anyString(), anyString())).thenReturn(Mono.just(1L));

                StepVerifier.create(adapter.deleteOrphan(id)).verifyComplete();

                ArgumentCaptor<String> payload = ArgumentCaptor.captor();
                verify(outboxEventStore).append(eq(id), eq(OutboxEventType.USER_DELETED.name()), payload.capture());
                assertThat(payload.getValue()).isEqualTo("{\"userId\":\"" + id + "\"}");
        }

        @Test
        void deleteOrphan_ShouldFail_WhenTheEventCannotBeWritten() {
                // The error reaches the caller, so the transaction rolls the deletion back.
                UUID id = UUID.randomUUID();
                when(userR2dbcRepository.deleteById(id)).thenReturn(Mono.empty());
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
                when(userR2dbcRepository.findById(ID)).thenReturn(Mono.just(stored));
                when(userEntityMapper.toBusinessUser(stored)).thenReturn(RENAMED);
                when(kafkaDataMapper.toUserModifiedEventDTO(RENAMED)).thenReturn(event);
                when(outboxEventStore.append(any(UUID.class), anyString(), anyString())).thenReturn(Mono.just(1L));

                StepVerifier.create(adapter.updateProfile(RENAMED)).expectNext(RENAMED).verifyComplete();

                ArgumentCaptor<String> payload = ArgumentCaptor.captor();
                verify(outboxEventStore).append(eq(ID), eq(OutboxEventType.USER_MODIFIED.name()), payload.capture());
                assertThat(objectMapper.readValue(payload.getValue(), UserModifiedEventDTO.class)).isEqualTo(event);
        }

        @Test
        void updateProfile_ShouldMapAUniqueViolationToUsernameTaken() {
                when(userR2dbcRepository.updateProfile(ID, "camille_d", "Cam", "Dupont"))
                                .thenReturn(Mono.error(new DataIntegrityViolationException("unique_user_identity")));

                StepVerifier.create(adapter.updateProfile(RENAMED))
                                .expectErrorMatches(error -> error instanceof BusinessException business
                                                && business.getErrorCode() == BusinessErrorCode.USERNAME_TAKEN)
                                .verify();

                verify(outboxEventStore, never()).append(any(), anyString(), anyString());
        }

        @Test
        void updateProfile_ShouldFailAsNotFound_WhenNoRowWasUpdated() {
                when(userR2dbcRepository.updateProfile(ID, "camille_d", "Cam", "Dupont")).thenReturn(Mono.just(0L));

                StepVerifier.create(adapter.updateProfile(RENAMED))
                                .expectErrorMatches(error -> error instanceof BusinessException business
                                                && business.getErrorCode() == BusinessErrorCode.USER_NOT_FOUND)
                                .verify();
        }

        @Test
        void updateProfile_ShouldFail_WhenTheEventCannotBeWritten() {
                // The error reaches the caller, so the transaction rolls the update back.
                UserEntity stored = mock(UserEntity.class);
                when(userR2dbcRepository.updateProfile(ID, "camille_d", "Cam", "Dupont")).thenReturn(Mono.just(1L));
                when(userR2dbcRepository.findById(ID)).thenReturn(Mono.just(stored));
                when(userEntityMapper.toBusinessUser(stored)).thenReturn(RENAMED);
                when(outboxEventStore.append(any(UUID.class), anyString(), anyString()))
                                .thenReturn(Mono.error(new IllegalStateException("outbox down")));

                StepVerifier.create(adapter.updateProfile(RENAMED)).expectError(IllegalStateException.class).verify();
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
                when(userR2dbcRepository.findById(ID)).thenReturn(Mono.just(stored));
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
}
