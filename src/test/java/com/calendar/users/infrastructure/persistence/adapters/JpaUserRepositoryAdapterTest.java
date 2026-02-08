package com.calendar.users.infrastructure.persistence.adapters;

import com.calendar.users.domain.models.BusinessUser;
import com.calendar.users.exception.BusinessErrorCode;
import com.calendar.users.exception.BusinessException;
import com.calendar.users.exception.TechnicalErrorCode;
import com.calendar.users.exception.TechnicalException;
import com.calendar.users.infrastructure.persistence.mappers.UserEntityMapper;
import com.calendar.users.infrastructure.persistence.models.entities.UserEntity;
import com.calendar.users.infrastructure.persistence.repositories.UserR2dbcRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JpaUserRepositoryAdapterTest {

        @Mock
        private UserR2dbcRepository userR2dbcRepository;

        @Mock
        private UserEntityMapper userEntityMapper;

        @InjectMocks
        private JpaUserRepositoryAdapter adapter;

        @Test
        void save_ShouldReturnBusinessUser_WhenSuccess() {
                // Given
                UUID id = UUID.randomUUID();
                BusinessUser user = new BusinessUser(id, "user", 1, "F", "L", "url", LocalDateTime.now());
                UserEntity entity = mock(UserEntity.class);

                when(userEntityMapper.toUserEntity(user)).thenReturn(entity);
                when(userR2dbcRepository.save(entity)).thenReturn(Mono.just(entity));
                when(userEntityMapper.toBusinessUser(entity)).thenReturn(user);

                // When
                Mono<BusinessUser> result = adapter.save(user, "kc-123");

                // Then
                StepVerifier.create(result)
                                .expectNext(user)
                                .verifyComplete();

                verify(entity).setKeycloakId("kc-123");
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
        }

        @Test
        void save_ShouldMapTechnicalError() {
                // Given
                BusinessUser user = new BusinessUser(UUID.randomUUID(), "user", 1, "F", "L", "url",
                                LocalDateTime.now());
                UserEntity entity = new UserEntity();
                when(userEntityMapper.toUserEntity(user)).thenReturn(entity);
                when(userR2dbcRepository.save(entity)).thenReturn(Mono.error(new RuntimeException("Generic Error")));

                // When
                Mono<BusinessUser> result = adapter.save(user, "kc-123");

                // Then
                StepVerifier.create(result)
                                .expectErrorMatches(throwable -> throwable instanceof TechnicalException &&
                                                ((TechnicalException) throwable)
                                                                .getErrorCode() == TechnicalErrorCode.DATABASE_ERROR)
                                .verify();
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
        void findIdByKeycloakId_ShouldMapError() {
                // Given
                when(userR2dbcRepository.findIdByKeycloakId(any()))
                                .thenReturn(Mono.error(new RuntimeException("DB Error")));

                // When
                Mono<UUID> result = adapter.findIdByKeycloakId("kc-123");

                // Then
                StepVerifier.create(result)
                                .expectError(TechnicalException.class)
                                .verify();
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
        void existsByUserNameAndHashtag_ShouldMapError() {
                // Given
                when(userR2dbcRepository.existsByUserNameAndHashtag(anyString(), anyInt()))
                                .thenReturn(Mono.error(new RuntimeException("DB Error")));

                // When
                Mono<Boolean> result = adapter.existsByUserNameAndHashtag("user", 1234);

                // Then
                StepVerifier.create(result)
                                .expectError(TechnicalException.class)
                                .verify();
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
        void getBusinessUserByUserId_ShouldMapError() {
                // Given
                when(userR2dbcRepository.findById(any(UUID.class)))
                                .thenReturn(Mono.error(new RuntimeException("DB Error")));

                // When
                Mono<BusinessUser> result = adapter.getBusinessUserByUserId(UUID.randomUUID());

                // Then
                StepVerifier.create(result)
                                .expectError(TechnicalException.class)
                                .verify();
        }

        @Test
        void updateProfilePicUrl_ShouldReturnInteger() {
                // Given
                when(userR2dbcRepository.updateProfilePicUrlByKeycloakId("url", 123L)).thenReturn(Mono.just(1));

                // When
                Mono<Integer> result = adapter.updateProfilePicUrl("url", 123L);

                // Then
                StepVerifier.create(result)
                                .expectNext(1)
                                .verifyComplete();
        }
}
