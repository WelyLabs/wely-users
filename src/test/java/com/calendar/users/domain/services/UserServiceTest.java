package com.calendar.users.domain.services;

import com.calendar.users.domain.models.BusinessUser;
import com.calendar.users.domain.ports.FileStorage;
import com.calendar.users.domain.ports.IdentityProvider;
import com.calendar.users.domain.ports.UserEventPublisher;
import com.calendar.users.domain.ports.UserRepository;
import com.calendar.users.exception.BusinessErrorCode;
import com.calendar.users.exception.BusinessException;
import com.calendar.users.infrastructure.identity.models.KeycloakUserResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.codec.multipart.FilePart;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private FileStorage fileStorage;
    @Mock
    private IdentityProvider identityProvider;
    @Mock
    private UserEventPublisher userEventPublisher;

    @InjectMocks
    private UserService userService;

    @Test
    void readProfile_ShouldReturnUser_WhenExists() {
        // Given
        UUID userId = UUID.randomUUID();
        BusinessUser user = new BusinessUser(userId, "user", 1, "F", "L", "url", LocalDateTime.now());
        when(userRepository.getBusinessUserByUserId(userId)).thenReturn(Mono.just(user));

        // When
        Mono<BusinessUser> result = userService.readProfile(userId);

        // Then
        StepVerifier.create(result)
                .expectNext(user)
                .verifyComplete();
    }

    @Test
    void readProfile_ShouldError_WhenNotFound() {
        // Given
        UUID userId = UUID.randomUUID();
        when(userRepository.getBusinessUserByUserId(userId)).thenReturn(Mono.empty());

        // When
        Mono<BusinessUser> result = userService.readProfile(userId);

        // Then
        StepVerifier.create(result)
                .expectErrorMatches(throwable -> throwable instanceof BusinessException &&
                        ((BusinessException) throwable).getErrorCode() == BusinessErrorCode.USER_NOT_FOUND)
                .verify();
    }

    @Test
    void resolveInternalUserId_ShouldReturnExisingId_WhenFoundInRepo() {
        // Given
        String kcId = "kc-123";
        UUID internalId = UUID.randomUUID();
        when(userRepository.findIdByKeycloakId(kcId)).thenReturn(Mono.just(internalId));
        // Eager evaluation of switchIfEmpty requires this stubbing
        when(identityProvider.getUser(kcId)).thenReturn(Mono.empty());

        // When
        Mono<UUID> result = userService.resolveInternalUserId(kcId);

        // Then
        StepVerifier.create(result)
                .expectNext(internalId)
                .verifyComplete();
    }

    @Test
    void resolveInternalUserId_ShouldCreateUser_WhenNotFoundInRepo() {
        // Given
        String kcId = "kc-123";
        UUID newId = UUID.randomUUID();
        KeycloakUserResponse kcResponse = new KeycloakUserResponse("username", "First", "Last", "email", true);

        when(userRepository.findIdByKeycloakId(kcId)).thenReturn(Mono.empty());
        when(identityProvider.getUser(kcId)).thenReturn(Mono.just(kcResponse));
        when(userRepository.existsByUserNameAndHashtag(anyString(), anyInt())).thenReturn(Mono.just(false));

        BusinessUser savedUser = new BusinessUser(newId, "username", 1111, "First", "Last", null, LocalDateTime.now());
        when(userRepository.save(any(BusinessUser.class), eq(kcId))).thenReturn(Mono.just(savedUser));
        when(userEventPublisher.publishUserCreatedEvent(savedUser)).thenReturn(Mono.just(newId));

        // When
        Mono<UUID> result = userService.resolveInternalUserId(kcId);

        // Then
        StepVerifier.create(result)
                .expectNext(newId)
                .verifyComplete();

        verify(userRepository).save(any(BusinessUser.class), eq(kcId));
        verify(userEventPublisher).publishUserCreatedEvent(any(BusinessUser.class));
    }

    @Test
    void resolveInternalUserId_ShouldRetryHashtag_WhenConflict() {
        // Given
        String kcId = "kc-123";
        KeycloakUserResponse kcResponse = new KeycloakUserResponse("username", "First", "Last", "email", true);

        when(userRepository.findIdByKeycloakId(kcId)).thenReturn(Mono.empty());
        when(identityProvider.getUser(kcId)).thenReturn(Mono.just(kcResponse));

        // First attempt: conflict, Second: success
        when(userRepository.existsByUserNameAndHashtag(eq("username"), anyInt()))
                .thenReturn(Mono.just(true))
                .thenReturn(Mono.just(false));

        UUID newId = UUID.randomUUID();
        BusinessUser savedUser = new BusinessUser(newId, "username", 1111, "First", "Last", null, LocalDateTime.now());
        when(userRepository.save(any(BusinessUser.class), eq(kcId))).thenReturn(Mono.just(savedUser));
        when(userEventPublisher.publishUserCreatedEvent(savedUser)).thenReturn(Mono.just(newId));

        // When
        Mono<UUID> result = userService.resolveInternalUserId(kcId);

        // Then
        StepVerifier.create(result)
                .expectNext(newId)
                .verifyComplete();

        verify(userRepository, times(2)).existsByUserNameAndHashtag(eq("username"), anyInt());
    }

    @Test
    void updateProfilePicture_ShouldReturnUrl_WhenSuccess() {
        // Given
        Long userId = 123L;
        FilePart filePart = mock(FilePart.class);
        String url = "http://s3.url";

        when(fileStorage.storeObject(filePart, "123")).thenReturn(Mono.just(url));
        when(userRepository.updateProfilePicUrl(url, userId)).thenReturn(Mono.just(1));

        // When
        Mono<String> result = userService.updateProfilePicture(userId, Mono.just(filePart));

        // Then
        StepVerifier.create(result)
                .expectNext(url)
                .verifyComplete();
    }

    @Test
    void updateProfilePicture_ShouldError_WhenNoRecordUpdated() {
        // Given
        Long userId = 123L;
        FilePart filePart = mock(FilePart.class);
        String url = "http://s3.url";

        when(fileStorage.storeObject(filePart, "123")).thenReturn(Mono.just(url));
        when(userRepository.updateProfilePicUrl(url, userId)).thenReturn(Mono.just(0));

        // When
        Mono<String> result = userService.updateProfilePicture(userId, Mono.just(filePart));

        // Then
        StepVerifier.create(result)
                .expectError(Exception.class)
                .verify();
    }
}
