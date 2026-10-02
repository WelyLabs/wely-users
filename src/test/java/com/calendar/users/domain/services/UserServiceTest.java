package com.calendar.users.domain.services;

import com.calendar.users.domain.models.BusinessUser;
import com.calendar.users.domain.ports.IdentityProvider;
import com.calendar.users.domain.ports.TransactionBoundary;
import com.calendar.users.domain.ports.UserEventPublisher;
import com.calendar.users.domain.ports.UserRepository;
import com.calendar.users.exception.BusinessErrorCode;
import com.calendar.users.exception.BusinessException;
import com.calendar.users.domain.models.IdentityUser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
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
        @Mock
        private UserEventPublisher userEventPublisher;
        @Mock
        private TransactionBoundary transactionBoundary;

        @InjectMocks
        private UserService userService;

        /**
         * Runs the unit of work without a transaction, so these tests stay about the
         * domain. That the boundary is a real PostgreSQL transaction — and that both
         * writes roll back together — is asserted in
         * {@code R2dbcTransactionBoundaryAdapterIntegrationTest}, against a database.
         */
        private void passThroughTransaction() {
                when(transactionBoundary.atomically(ArgumentMatchers.<Mono<UUID>>any()))
                                .thenAnswer(invocation -> invocation.getArgument(0));
        }

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

                passThroughTransaction();
                when(userRepository.findIdByKeycloakId(kcId)).thenReturn(Mono.empty());
                when(identityProvider.getUser(kcId)).thenReturn(Mono.just(kcResponse));
                when(userRepository.existsByUserNameAndHashtag(anyString(), anyInt())).thenReturn(Mono.just(false));

                BusinessUser savedUser = new BusinessUser(newId, "username", 1111, "First", "Last", null,
                                LocalDateTime.now());
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
                IdentityUser kcResponse = new IdentityUser("username", "First", "Last");

                passThroughTransaction();
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
        void resolveInternalUserId_ShouldHandSaveAndPublishToTheTransactionBoundary() {
                // The point of the outbox: the row in app_user and the row in outbox_event
                // either both exist or neither does. The domain states that by handing the
                // pair to TransactionBoundary as one unsubscribed unit of work.
                String kcId = "kc-123";
                UUID newId = UUID.randomUUID();
                IdentityUser keycloakUser = new IdentityUser("username", "First", "Last");
                BusinessUser savedUser = new BusinessUser(newId, "username", 1111, "First", "Last", null,
                                LocalDateTime.now());

                when(userRepository.findIdByKeycloakId(kcId)).thenReturn(Mono.empty());
                when(identityProvider.getUser(kcId)).thenReturn(Mono.just(keycloakUser));
                when(userRepository.existsByUserNameAndHashtag(anyString(), anyInt())).thenReturn(Mono.just(false));
                when(userRepository.save(any(BusinessUser.class), eq(kcId))).thenReturn(Mono.just(savedUser));
                when(userEventPublisher.publishUserCreatedEvent(savedUser)).thenReturn(Mono.just(newId));

                ArgumentCaptor<Mono<UUID>> unitOfWork = ArgumentCaptor.captor();
                when(transactionBoundary.atomically(unitOfWork.capture())).thenReturn(Mono.just(newId));

                StepVerifier.create(userService.resolveInternalUserId(kcId))
                                .expectNext(newId)
                                .verifyComplete();

                // This boundary never subscribed, so nothing inside it ran — the publish did
                // not happen on its own, outside the transaction.
                verify(userEventPublisher, never()).publishUserCreatedEvent(any());

                // And what the boundary was handed is the save followed by the publish.
                // Subscribing to it here is what tells a real unit of work apart from a value
                // that was already computed before the boundary was ever called.
                StepVerifier.create(unitOfWork.getValue())
                                .expectNext(newId)
                                .verifyComplete();
                verify(userEventPublisher).publishUserCreatedEvent(savedUser);
        }

        @Test
        void resolveInternalUserId_ShouldNotReachTheTransaction_WhenTheIdentityProviderFails() {
                // The Keycloak call is outside the boundary on purpose: a database connection
                // must not be held open across a network round trip to another service.
                when(userRepository.findIdByKeycloakId("kc-1")).thenReturn(Mono.empty());
                when(identityProvider.getUser("kc-1"))
                                .thenReturn(Mono.error(new IllegalStateException("keycloak down")));

                StepVerifier.create(userService.resolveInternalUserId("kc-1"))
                                .expectError(IllegalStateException.class)
                                .verify();

                verify(transactionBoundary, never()).atomically(ArgumentMatchers.<Mono<UUID>>any());
        }
}
