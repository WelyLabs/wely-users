package com.calendar.users.domain.services;

import com.calendar.users.domain.models.BusinessUser;
import com.calendar.users.domain.ports.IdentityProvider;
import com.calendar.users.domain.ports.UserRepository;
import com.calendar.users.domain.models.IdentityToCheck;
import com.calendar.users.domain.models.OrphanSweep;
import reactor.core.publisher.Flux;
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
        void findBusinessIdByKeycloakId_ShouldReturnTheId_WhenTheUserExists() {
                UUID existingId = UUID.randomUUID();
                when(userRepository.findIdByKeycloakId("kc-1")).thenReturn(Mono.just(existingId));

                StepVerifier.create(userService.findBusinessIdByKeycloakId("kc-1"))
                                .expectNext(existingId)
                                .verifyComplete();
        }

        @Test
        void findBusinessIdByKeycloakId_ShouldFailWithoutCreating_WhenTheUserIsUnknown() {
                // Users are created at registration; a login for an unknown one must not create it.
                when(userRepository.findIdByKeycloakId("kc-1")).thenReturn(Mono.empty());

                StepVerifier.create(userService.findBusinessIdByKeycloakId("kc-1"))
                                .expectErrorMatches(e -> e instanceof BusinessException
                                                && ((BusinessException) e).getErrorCode()
                                                                == BusinessErrorCode.USER_NOT_FOUND)
                                .verify();

                verify(userRepository, never()).save(any(), anyString());
        }

        @Test
        void provisionUser_ShouldRetryTheHashtag_WhenItIsTaken() {
                when(userRepository.findIdByKeycloakId("kc-1")).thenReturn(Mono.empty());
                when(userRepository.existsByUserNameAndHashtag(eq("léa"), anyInt()))
                                .thenReturn(Mono.just(true))
                                .thenReturn(Mono.just(false));
                UUID newId = UUID.randomUUID();
                when(userRepository.save(any(BusinessUser.class), eq("kc-1"))).thenReturn(Mono.just(
                                new BusinessUser(newId, "léa", 1111, "Léa", "Martin", null, LocalDateTime.now())));

                StepVerifier.create(userService.provisionUser("kc-1", new IdentityUser("léa", "Léa", "Martin")))
                                .expectNext(newId)
                                .verifyComplete();

                verify(userRepository, times(2)).existsByUserNameAndHashtag(eq("léa"), anyInt());
        }

        @Test
        void provisionUser_ShouldGiveUp_WhenEveryHashtagAttemptIsTaken() {
                // generateUniqueHashtag used to recurse without a limit, so a saturated
                // username looped forever against the database.
                when(userRepository.findIdByKeycloakId("kc-1")).thenReturn(Mono.empty());
                when(userRepository.existsByUserNameAndHashtag(eq("léa"), anyInt())).thenReturn(Mono.just(true));

                StepVerifier.create(userService.provisionUser("kc-1", new IdentityUser("léa", "Léa", "Martin")))
                                .expectErrorMatches(e -> e instanceof BusinessException
                                                && ((BusinessException) e).getErrorCode()
                                                                == BusinessErrorCode.HASHTAG_UNAVAILABLE)
                                .verify();

                // Bounded: ten attempts, then it gives up. No save is attempted.
                verify(userRepository, times(10)).existsByUserNameAndHashtag(eq("léa"), anyInt());
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

        private static final LocalDateTime CUTOFF = LocalDateTime.of(2026, 10, 11, 1, 0);

        @Test
        void removeOrphans_ShouldConfirmAUserKeycloakKnows() {
                UUID userId = UUID.randomUUID();
                when(userRepository.findIdentitiesToCheck(CUTOFF, 100))
                                .thenReturn(Flux.just(new IdentityToCheck(userId, "kc-1")));
                when(identityProvider.getUser("kc-1")).thenReturn(Mono.just(new IdentityUser("léa", "Léa", "M")));
                when(userRepository.markIdentityChecked(userId)).thenReturn(Mono.empty());

                StepVerifier.create(userService.removeOrphans(CUTOFF, 100))
                                .expectNext(new OrphanSweep(1, 0, 0))
                                .verifyComplete();

                verify(userRepository, never()).deleteOrphan(any());
        }

        @Test
        void removeOrphans_ShouldDeleteAUserKeycloakDoesNotKnow() {
                UUID notFound = UUID.randomUUID();
                UUID empty = UUID.randomUUID();
                when(userRepository.findIdentitiesToCheck(CUTOFF, 100)).thenReturn(Flux.just(
                                new IdentityToCheck(notFound, "kc-404"), new IdentityToCheck(empty, "kc-empty")));
                when(identityProvider.getUser("kc-404"))
                                .thenReturn(Mono.error(new BusinessException(BusinessErrorCode.USER_NOT_FOUND)));
                when(identityProvider.getUser("kc-empty")).thenReturn(Mono.empty());
                when(userRepository.deleteOrphan(any())).thenReturn(Mono.empty());

                StepVerifier.create(userService.removeOrphans(CUTOFF, 100))
                                .expectNext(new OrphanSweep(0, 2, 0))
                                .verifyComplete();

                verify(userRepository).deleteOrphan(notFound);
                verify(userRepository).deleteOrphan(empty);
                verify(userRepository, never()).markIdentityChecked(any());
        }

        @Test
        void removeOrphans_ShouldNeitherDeleteNorConfirm_WhenKeycloakIsDown_AndKeepGoing() {
                // An outage is not proof of absence: deleting here would wipe real users.
                UUID down = UUID.randomUUID();
                UUID fine = UUID.randomUUID();
                when(userRepository.findIdentitiesToCheck(CUTOFF, 100)).thenReturn(Flux.just(
                                new IdentityToCheck(down, "kc-down"), new IdentityToCheck(fine, "kc-fine")));
                when(identityProvider.getUser("kc-down"))
                                .thenReturn(Mono.error(new IllegalStateException("keycloak unreachable")));
                when(identityProvider.getUser("kc-fine")).thenReturn(Mono.just(new IdentityUser("x", "X", "Y")));
                when(userRepository.markIdentityChecked(fine)).thenReturn(Mono.empty());

                StepVerifier.create(userService.removeOrphans(CUTOFF, 100))
                                .expectNext(new OrphanSweep(1, 0, 1))
                                .verifyComplete();

                verify(userRepository, never()).deleteOrphan(any());
                verify(userRepository, never()).markIdentityChecked(down);
        }

        @Test
        void removeOrphans_ShouldReturnAnEmptySweep_WhenNothingIsToCheck() {
                when(userRepository.findIdentitiesToCheck(CUTOFF, 100)).thenReturn(Flux.empty());

                StepVerifier.create(userService.removeOrphans(CUTOFF, 100))
                                .expectNext(OrphanSweep.EMPTY)
                                .verifyComplete();
        }
}
