package com.calendar.users.domain.services;

import com.calendar.users.domain.models.BusinessUser;
import com.calendar.users.domain.ports.IdentityProvider;
import com.calendar.users.domain.ports.UserRepository;
import com.calendar.users.exception.BusinessErrorCode;
import com.calendar.users.exception.BusinessException;
import com.calendar.users.domain.models.IdentityUser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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
        private IdentityProvider identityProvider;

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
                                                ((BusinessException) throwable)
                                                                .getErrorCode() == BusinessErrorCode.USER_NOT_FOUND)
                                .verify();
        }

        @Test
        void resolveInternalUserId_ShouldReturnExisingId_WhenFoundInRepo() {
                // Given
                String kcId = "kc-123";
                UUID internalId = UUID.randomUUID();
                when(userRepository.findIdByKeycloakId(kcId)).thenReturn(Mono.just(internalId));

                // When
                Mono<UUID> result = userService.resolveInternalUserId(kcId);

                // Then
                StepVerifier.create(result)
                                .expectNext(internalId)
                                .verifyComplete();

                // The provisioning path sits behind a Mono.defer, so a user already
                // known does not reach Keycloak at all. switchIfEmpty used to assemble
                // the chain eagerly, and this test had to stub getUser to survive.
                verify(identityProvider, never()).getUser(anyString());
                verify(userRepository, never()).save(any(), anyString());
        }

        @Test
        void resolveInternalUserId_ShouldCreateUser_WhenNotFoundInRepo() {
                // Given
                String kcId = "kc-123";
                UUID newId = UUID.randomUUID();
                IdentityUser kcResponse = new IdentityUser("username", "First", "Last");

                when(userRepository.findIdByKeycloakId(kcId)).thenReturn(Mono.empty());
                when(identityProvider.getUser(kcId)).thenReturn(Mono.just(kcResponse));
                when(userRepository.existsByUserNameAndHashtag(anyString(), anyInt())).thenReturn(Mono.just(false));

                BusinessUser savedUser = new BusinessUser(newId, "username", 1111, "First", "Last", null,
                                LocalDateTime.now());
                when(userRepository.save(any(BusinessUser.class), eq(kcId))).thenReturn(Mono.just(savedUser));

                // When
                Mono<UUID> result = userService.resolveInternalUserId(kcId);

                // Then
                StepVerifier.create(result)
                                .expectNext(newId)
                                .verifyComplete();

                verify(userRepository).save(any(BusinessUser.class), eq(kcId));
        }

        @Test
        void resolveInternalUserId_ShouldRetryHashtag_WhenConflict() {
                // Given
                String kcId = "kc-123";
                IdentityUser kcResponse = new IdentityUser("username", "First", "Last");

                when(userRepository.findIdByKeycloakId(kcId)).thenReturn(Mono.empty());
                when(identityProvider.getUser(kcId)).thenReturn(Mono.just(kcResponse));

                // First attempt: conflict, Second: success
                when(userRepository.existsByUserNameAndHashtag(eq("username"), anyInt()))
                                .thenReturn(Mono.just(true))
                                .thenReturn(Mono.just(false));

                UUID newId = UUID.randomUUID();
                BusinessUser savedUser = new BusinessUser(newId, "username", 1111, "First", "Last", null,
                                LocalDateTime.now());
                when(userRepository.save(any(BusinessUser.class), eq(kcId))).thenReturn(Mono.just(savedUser));

                // When
                Mono<UUID> result = userService.resolveInternalUserId(kcId);

                // Then
                StepVerifier.create(result)
                                .expectNext(newId)
                                .verifyComplete();

                verify(userRepository, times(2)).existsByUserNameAndHashtag(eq("username"), anyInt());
        }

        @Test
        void resolveInternalUserId_ShouldGiveUp_WhenEveryHashtagAttemptIsTaken() {
                // generateUniqueHashtag used to recurse without a limit, so a saturated
                // username looped forever against the database.
                IdentityUser keycloakUser = new IdentityUser("username", "first", "last");

                when(userRepository.findIdByKeycloakId("kc-1")).thenReturn(Mono.empty());
                when(identityProvider.getUser("kc-1")).thenReturn(Mono.just(keycloakUser));
                when(userRepository.existsByUserNameAndHashtag(eq("username"), anyInt()))
                                .thenReturn(Mono.just(true));

                StepVerifier.create(userService.resolveInternalUserId("kc-1"))
                                .expectErrorMatches(e -> e instanceof BusinessException
                                                && ((BusinessException) e).getErrorCode()
                                                                == BusinessErrorCode.HASHTAG_UNAVAILABLE)
                                .verify();

                // Bounded: ten attempts, then it gives up. No save is attempted.
                verify(userRepository, times(10)).existsByUserNameAndHashtag(eq("username"), anyInt());
                verify(userRepository, never()).save(any(), anyString());
        }

        @Test
        void resolveInternalUserId_ShouldNotSave_WhenTheIdentityProviderFails() {
                when(userRepository.findIdByKeycloakId("kc-1")).thenReturn(Mono.empty());
                when(identityProvider.getUser("kc-1"))
                                .thenReturn(Mono.error(new IllegalStateException("keycloak down")));

                StepVerifier.create(userService.resolveInternalUserId("kc-1"))
                                .expectError(IllegalStateException.class)
                                .verify();

                verify(userRepository, never()).save(any(), anyString());
        }

        @Test
        void provisionUser_ShouldCreateTheUser_FromTheGivenIdentity() {
                String kcId = "kc-new";
                UUID newId = UUID.randomUUID();
                when(userRepository.findIdByKeycloakId(kcId)).thenReturn(Mono.empty());
                when(userRepository.existsByUserNameAndHashtag(eq("léa"), anyInt())).thenReturn(Mono.just(false));
                when(userRepository.save(any(BusinessUser.class), eq(kcId))).thenAnswer(invocation -> {
                        BusinessUser toSave = invocation.getArgument(0);
                        return Mono.just(new BusinessUser(newId, toSave.userName(), toSave.hashtag(),
                                        toSave.firstName(), toSave.lastName(), null, toSave.joinedDate()));
                });

                StepVerifier.create(userService.provisionUser(kcId, new IdentityUser("léa", "Léa", "Martin")))
                                .expectNext(newId)
                                .verifyComplete();

                // The identity comes from the caller: Keycloak is not queried.
                verify(identityProvider, never()).getUser(anyString());
                verify(userRepository).save(argThat(user -> user.userName().equals("léa")
                                && user.firstName().equals("Léa") && user.lastName().equals("Martin")), eq(kcId));
        }

        @Test
        void provisionUser_ShouldReturnTheExistingId_WhenTheKeycloakIdIsKnown() {
                UUID existingId = UUID.randomUUID();
                when(userRepository.findIdByKeycloakId("kc-1")).thenReturn(Mono.just(existingId));

                StepVerifier.create(userService.provisionUser("kc-1", new IdentityUser("léa", "Léa", "Martin")))
                                .expectNext(existingId)
                                .verifyComplete();

                verify(userRepository, never()).save(any(), anyString());
        }

        @Test
        void provisionUser_ShouldReturnTheWinnersId_WhenAConcurrentCallCreatedItFirst() {
                UUID winnerId = UUID.randomUUID();
                when(userRepository.findIdByKeycloakId("kc-1"))
                                .thenReturn(Mono.empty())
                                .thenReturn(Mono.just(winnerId));
                when(userRepository.existsByUserNameAndHashtag(eq("léa"), anyInt())).thenReturn(Mono.just(false));
                when(userRepository.save(any(BusinessUser.class), eq("kc-1")))
                                .thenReturn(Mono.error(new BusinessException(BusinessErrorCode.USER_ALREADY_EXISTS)));

                StepVerifier.create(userService.provisionUser("kc-1", new IdentityUser("léa", "Léa", "Martin")))
                                .expectNext(winnerId)
                                .verifyComplete();
        }

        @Test
        void provisionUser_ShouldFail_WhenTheConflictIsNotOnTheKeycloakId() {
                // USER_ALREADY_EXISTS without a row for this Keycloak id: the pseudo and hashtag were taken.
                when(userRepository.findIdByKeycloakId("kc-1")).thenReturn(Mono.empty());
                when(userRepository.existsByUserNameAndHashtag(eq("léa"), anyInt())).thenReturn(Mono.just(false));
                when(userRepository.save(any(BusinessUser.class), eq("kc-1")))
                                .thenReturn(Mono.error(new BusinessException(BusinessErrorCode.USER_ALREADY_EXISTS)));

                StepVerifier.create(userService.provisionUser("kc-1", new IdentityUser("léa", "Léa", "Martin")))
                                .expectErrorMatches(e -> e instanceof BusinessException
                                                && ((BusinessException) e).getErrorCode()
                                                                == BusinessErrorCode.USER_ALREADY_EXISTS)
                                .verify();
        }

        @Test
        void provisionUser_ShouldPropagateAnyOtherFailure() {
                when(userRepository.findIdByKeycloakId("kc-1")).thenReturn(Mono.empty());
                when(userRepository.existsByUserNameAndHashtag(eq("léa"), anyInt())).thenReturn(Mono.just(false));
                when(userRepository.save(any(BusinessUser.class), eq("kc-1")))
                                .thenReturn(Mono.error(new IllegalStateException("database down")));

                StepVerifier.create(userService.provisionUser("kc-1", new IdentityUser("léa", "Léa", "Martin")))
                                .expectError(IllegalStateException.class)
                                .verify();

                verify(userRepository, times(1)).findIdByKeycloakId("kc-1");
        }
}
