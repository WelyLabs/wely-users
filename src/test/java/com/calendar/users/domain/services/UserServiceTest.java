package com.calendar.users.domain.services;

import static org.mockito.ArgumentMatchers.any;

import java.time.Duration;
import java.util.List;

import static org.mockito.Mockito.verifyNoInteractions;

import com.calendar.users.domain.models.AccountState;
import com.calendar.users.domain.models.UserAbout;
import com.calendar.users.domain.models.Theme;
import com.calendar.users.domain.models.UserPreferences;

import com.calendar.users.domain.models.BusinessUser;
import com.calendar.users.domain.models.Registration;
import com.calendar.users.domain.ports.IdentityProvider;
import com.calendar.users.domain.models.MediaProfile;
import com.calendar.users.domain.models.StoredMedia;
import com.calendar.users.domain.ports.MediaStore;
import com.calendar.users.domain.ports.FriendshipChecker;
import com.calendar.users.domain.models.FriendProfile;
import com.calendar.users.exception.TechnicalErrorCode;
import com.calendar.users.exception.TechnicalException;
import com.calendar.users.domain.models.UserPicture;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.mockito.ArgumentCaptor;
import com.calendar.users.domain.models.ProfileUpdate;
import org.mockito.InOrder;
import com.calendar.users.domain.ports.UserRepository;
import com.calendar.users.domain.ports.UsersActivity;
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

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.MonthDay;
import java.time.ZoneOffset;
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
        private MediaStore mediaStore;

        @Mock
        private FriendshipChecker friendshipChecker;

        @Mock
        private UsersActivity usersActivity;


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
                                .expectComplete().verify(Duration.ofSeconds(10));
        }

        @Test
        void readProfile_ShouldError_WhenNotFound() {
                // Given
                UUID userId = UUID.randomUUID();
                when(userRepository.getBusinessUserByUserId(userId)).thenReturn(Mono.empty());
                when(userRepository.findAccountState(userId)).thenReturn(Mono.empty());

                // When
                Mono<BusinessUser> result = userService.readProfile(userId);

                // Then
                StepVerifier.create(result)
                                .expectErrorMatches(throwable -> throwable instanceof BusinessException &&
                                                ((BusinessException) throwable)
                                                                .getErrorCode() == BusinessErrorCode.USER_NOT_FOUND)
                                .verify(Duration.ofSeconds(10));
        }

        @Test
        void provisionUser_ShouldRetryTheHashtag_WhenItIsTaken() {
                when(userRepository.findIdBySubjectId("kc-1")).thenReturn(Mono.empty());
                when(userRepository.existsByUserNameAndHashtag(eq("léa"), anyInt()))
                                .thenReturn(Mono.just(true))
                                .thenReturn(Mono.just(false));
                UUID newId = UUID.randomUUID();
                when(userRepository.save(any(BusinessUser.class), eq("kc-1"))).thenReturn(Mono.just(
                                new BusinessUser(newId, "léa", 1111, "Léa", "Martin", null, LocalDateTime.now())));

                StepVerifier.create(userService.provisionUser("kc-1", new IdentityUser("léa", "Léa", "Martin")))
                                .expectNext(newId)
                                .expectComplete().verify(Duration.ofSeconds(10));

                verify(userRepository, times(2)).existsByUserNameAndHashtag(eq("léa"), anyInt());
        }

        @Test
        void provisionUser_ShouldGiveUp_WhenEveryHashtagAttemptIsTaken() {
                // generateUniqueHashtag used to recurse without a limit, so a saturated
                // username looped forever against the database.
                when(userRepository.findIdBySubjectId("kc-1")).thenReturn(Mono.empty());
                when(userRepository.existsByUserNameAndHashtag(eq("léa"), anyInt())).thenReturn(Mono.just(true));

                StepVerifier.create(userService.provisionUser("kc-1", new IdentityUser("léa", "Léa", "Martin")))
                                .expectErrorMatches(e -> e instanceof BusinessException
                                                && ((BusinessException) e).getErrorCode()
                                                                == BusinessErrorCode.HASHTAG_UNAVAILABLE)
                                .verify(Duration.ofSeconds(10));

                // Bounded: ten attempts, then it gives up. No save is attempted.
                verify(userRepository, times(10)).existsByUserNameAndHashtag(eq("léa"), anyInt());
                verify(userRepository, never()).save(any(), anyString());

                verify(usersActivity, never()).userSignedUp();
        }

        @Test
        void provisionUser_ShouldCreateTheUser_FromTheGivenIdentity() {
                String kcId = "kc-new";
                UUID newId = UUID.randomUUID();
                when(userRepository.findIdBySubjectId(kcId)).thenReturn(Mono.empty());
                when(userRepository.existsByUserNameAndHashtag(eq("léa"), anyInt())).thenReturn(Mono.just(false));
                when(userRepository.save(any(BusinessUser.class), eq(kcId))).thenAnswer(invocation -> {
                        BusinessUser toSave = invocation.getArgument(0);
                        return Mono.just(new BusinessUser(newId, toSave.userName(), toSave.hashtag(),
                                        toSave.firstName(), toSave.lastName(), null, toSave.joinedDate()));
                });

                StepVerifier.create(userService.provisionUser(kcId, new IdentityUser("léa", "Léa", "Martin")))
                                .expectNext(newId)
                                .expectComplete().verify(Duration.ofSeconds(10));

                verify(userRepository).save(argThat(user -> user.userName().equals("léa")
                                && user.firstName().equals("Léa") && user.lastName().equals("Martin")), eq(kcId));

                verify(usersActivity).userSignedUp();
        }

        @Test
        void provisionUser_ShouldReturnTheExistingId_WhenTheSubjectIdIsKnown() {
                UUID existingId = UUID.randomUUID();
                when(userRepository.findIdBySubjectId("kc-1")).thenReturn(Mono.just(existingId));

                StepVerifier.create(userService.provisionUser("kc-1", new IdentityUser("léa", "Léa", "Martin")))
                                .expectNext(existingId)
                                .expectComplete().verify(Duration.ofSeconds(10));

                verify(userRepository, never()).save(any(), anyString());

                // A retried provisioning is not a new signup.
                verify(usersActivity, never()).userSignedUp();
        }

        @Test
        void provisionUser_ShouldReturnTheWinnersId_WhenAConcurrentCallCreatedItFirst() {
                UUID winnerId = UUID.randomUUID();
                when(userRepository.findIdBySubjectId("kc-1"))
                                .thenReturn(Mono.empty())
                                .thenReturn(Mono.just(winnerId));
                when(userRepository.existsByUserNameAndHashtag(eq("léa"), anyInt())).thenReturn(Mono.just(false));
                when(userRepository.save(any(BusinessUser.class), eq("kc-1")))
                                .thenReturn(Mono.error(new BusinessException(BusinessErrorCode.USER_ALREADY_EXISTS)));

                StepVerifier.create(userService.provisionUser("kc-1", new IdentityUser("léa", "Léa", "Martin")))
                                .expectNext(winnerId)
                                .expectComplete().verify(Duration.ofSeconds(10));

                // Counted once, by the call that created the user.
                verify(usersActivity, never()).userSignedUp();
        }

        @Test
        void provisionUser_ShouldFail_WhenTheConflictIsNotOnTheSubjectId() {
                // USER_ALREADY_EXISTS without a row for this subject id: the pseudo and hashtag were taken.
                when(userRepository.findIdBySubjectId("kc-1")).thenReturn(Mono.empty());
                when(userRepository.existsByUserNameAndHashtag(eq("léa"), anyInt())).thenReturn(Mono.just(false));
                when(userRepository.save(any(BusinessUser.class), eq("kc-1")))
                                .thenReturn(Mono.error(new BusinessException(BusinessErrorCode.USER_ALREADY_EXISTS)));

                StepVerifier.create(userService.provisionUser("kc-1", new IdentityUser("léa", "Léa", "Martin")))
                                .expectErrorMatches(e -> e instanceof BusinessException
                                                && ((BusinessException) e).getErrorCode()
                                                                == BusinessErrorCode.USER_ALREADY_EXISTS)
                                .verify(Duration.ofSeconds(10));
        }

        @Test
        void provisionUser_ShouldPropagateAnyOtherFailure() {
                when(userRepository.findIdBySubjectId("kc-1")).thenReturn(Mono.empty());
                when(userRepository.existsByUserNameAndHashtag(eq("léa"), anyInt())).thenReturn(Mono.just(false));
                when(userRepository.save(any(BusinessUser.class), eq("kc-1")))
                                .thenReturn(Mono.error(new IllegalStateException("database down")));

                StepVerifier.create(userService.provisionUser("kc-1", new IdentityUser("léa", "Léa", "Martin")))
                                .expectError(IllegalStateException.class)
                                .verify(Duration.ofSeconds(10));

                verify(userRepository, times(1)).findIdBySubjectId("kc-1");

                verify(usersActivity, never()).userSignedUp();
        }

        private static final LocalDateTime CUTOFF = LocalDateTime.of(2026, 10, 11, 1, 0);

        @Test
        void removeOrphans_ShouldConfirmAUserTheProviderKnows() {
                UUID userId = UUID.randomUUID();
                when(userRepository.findIdentitiesToCheck(CUTOFF, 100))
                                .thenReturn(Flux.just(new IdentityToCheck(userId, "kc-1")));
                when(identityProvider.getUser("kc-1")).thenReturn(Mono.just(new IdentityUser("léa", "Léa", "M")));
                when(userRepository.markIdentityChecked(userId)).thenReturn(Mono.empty());

                StepVerifier.create(userService.removeOrphans(CUTOFF, 100, Duration.ZERO))
                                .expectNext(new OrphanSweep(1, 0, List.of()))
                                .expectComplete().verify(Duration.ofSeconds(10));

                verify(userRepository, never()).deleteOrphan(any());
        }

        @Test
        void removeOrphans_ShouldDeleteAUserTheProviderDoesNotKnow() {
                UUID notFound = UUID.randomUUID();
                UUID empty = UUID.randomUUID();
                when(userRepository.findIdentitiesToCheck(CUTOFF, 100)).thenReturn(Flux.just(
                                new IdentityToCheck(notFound, "kc-404"), new IdentityToCheck(empty, "kc-empty")));
                when(identityProvider.getUser("kc-404"))
                                .thenReturn(Mono.error(new BusinessException(BusinessErrorCode.USER_NOT_FOUND)));
                when(identityProvider.getUser("kc-empty")).thenReturn(Mono.empty());
                when(userRepository.deleteOrphan(any())).thenReturn(Mono.empty());

                StepVerifier.create(userService.removeOrphans(CUTOFF, 100, Duration.ZERO))
                                .expectNext(new OrphanSweep(0, 2, List.of()))
                                .expectComplete().verify(Duration.ofSeconds(10));

                verify(userRepository).deleteOrphan(notFound);
                verify(userRepository).deleteOrphan(empty);
                verify(userRepository, never()).markIdentityChecked(any());
        }

        @Test
        void removeOrphans_ShouldNeitherDeleteNorConfirm_WhenTheProviderIsDownOrRateLimits_AndKeepGoing() {
                // An outage is not proof of absence: deleting here would wipe real users.
                UUID down = UUID.randomUUID();
                UUID fine = UUID.randomUUID();
                when(userRepository.findIdentitiesToCheck(CUTOFF, 100)).thenReturn(Flux.just(
                                new IdentityToCheck(down, "kc-down"), new IdentityToCheck(fine, "kc-fine")));
                when(identityProvider.getUser("kc-down"))
                                .thenReturn(Mono.error(new TechnicalException(TechnicalErrorCode.IDENTITY_PROVIDER_ERROR)));
                when(identityProvider.getUser("kc-fine")).thenReturn(Mono.just(new IdentityUser("x", "X", "Y")));
                when(userRepository.markIdentityChecked(fine)).thenReturn(Mono.empty());

                StepVerifier.create(userService.removeOrphans(CUTOFF, 100, Duration.ZERO))
                                .expectNext(new OrphanSweep(1, 0, List.of("USR-TEC-001")))
                                .expectComplete().verify(Duration.ofSeconds(10));

                verify(userRepository, never()).deleteOrphan(any());
                verify(userRepository, never()).markIdentityChecked(down);
        }

        @Test
        void removeOrphans_ShouldWaitThePaceBeforeEachCheck() {
                UUID first = UUID.randomUUID();
                UUID second = UUID.randomUUID();
                when(userRepository.findIdentitiesToCheck(CUTOFF, 100)).thenReturn(Flux.just(
                                new IdentityToCheck(first, "auth0|1"), new IdentityToCheck(second, "auth0|2")));
                when(identityProvider.getUser(anyString())).thenReturn(Mono.just(new IdentityUser("x", "X", "Y")));
                when(userRepository.markIdentityChecked(any())).thenReturn(Mono.empty());

                StepVerifier.withVirtualTime(() -> userService.removeOrphans(CUTOFF, 100, Duration.ofSeconds(1)))
                                .expectSubscription()
                                .thenAwait(Duration.ofMillis(1500))
                                .then(() -> verify(identityProvider, times(1)).getUser(anyString()))
                                .thenAwait(Duration.ofSeconds(1))
                                .expectNext(new OrphanSweep(2, 0, List.of()))
                                .expectComplete().verify(Duration.ofSeconds(10));
        }

        @Test
        void removeOrphans_ShouldReturnAnEmptySweep_WhenNothingIsToCheck() {
                when(userRepository.findIdentitiesToCheck(CUTOFF, 100)).thenReturn(Flux.empty());

                StepVerifier.create(userService.removeOrphans(CUTOFF, 100, Duration.ZERO))
                                .expectNext(OrphanSweep.EMPTY)
                                .expectComplete().verify(Duration.ofSeconds(10));
        }

        // --- updateProfile ---------------------------------------------------

        private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
        private static final LocalDateTime JOINED = LocalDateTime.of(2026, 9, 1, 10, 0);
        private static final BusinessUser CAMILLE =
                        new BusinessUser(USER_ID, "camille", 4321, "Camille", "Durand", "pic.jpg", JOINED);
        private static final IdentityUser CAMILLE_BEFORE = new IdentityUser("camille", "Camille", "Durand");

        private void givenCamille() {
                when(userRepository.getBusinessUserByUserId(USER_ID)).thenReturn(Mono.just(CAMILLE));
                when(userRepository.findSubjectIdByUserId(USER_ID)).thenReturn(Mono.just("kc-camille"));
        }

        @Test
        void updateProfile_ShouldChangeTheIdentityProviderThenTheDatabase_KeepingHashtagAndPicture() {
                givenCamille();
                BusinessUser expected = new BusinessUser(USER_ID, "camille_d", 4321, "Cam", "Dupont", "pic.jpg", JOINED);
                when(identityProvider.updateProfile(any(), any())).thenReturn(Mono.empty());
                when(userRepository.updateProfile(expected, "camille")).thenReturn(Mono.just(expected));

                StepVerifier.create(userService.updateProfile(USER_ID, new ProfileUpdate("camille_d", "Cam", "Dupont")))
                                .expectNext(expected)
                                .expectComplete().verify(Duration.ofSeconds(10));

                InOrder order = inOrder(identityProvider, userRepository);
                order.verify(identityProvider).updateProfile("kc-camille", new IdentityUser("camille_d", "Cam", "Dupont"));
                order.verify(userRepository).updateProfile(expected, "camille");
        }

        @Test
        void updateProfile_ShouldLowercaseTheUsername_AsTheIdentityProviderStoresIt() {
                givenCamille();
                when(identityProvider.updateProfile(any(), any())).thenReturn(Mono.empty());
                when(userRepository.updateProfile(any(), any())).thenAnswer(call -> Mono.just(call.getArgument(0)));

                StepVerifier.create(userService.updateProfile(USER_ID, new ProfileUpdate("Camille_D", "Cam", "Dupont")))
                                .expectNextMatches(user -> user.userName().equals("camille_d"))
                                .expectComplete().verify(Duration.ofSeconds(10));

                verify(identityProvider).updateProfile("kc-camille", new IdentityUser("camille_d", "Cam", "Dupont"));
        }

        @Test
        void updateProfile_ShouldWriteNothing_WhenTheIdentityProviderRefuses() {
                givenCamille();
                when(identityProvider.updateProfile(any(), any()))
                                .thenReturn(Mono.error(new BusinessException(BusinessErrorCode.USERNAME_TAKEN)));

                StepVerifier.create(userService.updateProfile(USER_ID, new ProfileUpdate("lea", "Cam", "Dupont")))
                                .expectErrorMatches(error -> error instanceof BusinessException business
                                                && business.getErrorCode() == BusinessErrorCode.USERNAME_TAKEN)
                                .verify(Duration.ofSeconds(10));

                verify(userRepository, never()).updateProfile(any(), any());
                verify(identityProvider, times(1)).updateProfile(any(), any());
        }

        @Test
        void updateProfile_ShouldGiveTheIdentityProviderItsPreviousValuesBack_WhenTheDatabaseRefuses() {
                givenCamille();
                when(identityProvider.updateProfile(any(), any())).thenReturn(Mono.empty());
                when(userRepository.updateProfile(any(), any()))
                                .thenReturn(Mono.error(new BusinessException(BusinessErrorCode.USERNAME_TAKEN)));

                StepVerifier.create(userService.updateProfile(USER_ID, new ProfileUpdate("lea", "Cam", "Dupont")))
                                .expectErrorMatches(error -> error instanceof BusinessException business
                                                && business.getErrorCode() == BusinessErrorCode.USERNAME_TAKEN)
                                .verify(Duration.ofSeconds(10));

                InOrder order = inOrder(identityProvider, userRepository);
                order.verify(identityProvider).updateProfile("kc-camille", new IdentityUser("lea", "Cam", "Dupont"));
                order.verify(userRepository).updateProfile(any(), any());
                order.verify(identityProvider).updateProfile("kc-camille", CAMILLE_BEFORE);
        }

        @Test
        void updateProfile_ShouldKeepTheOriginalError_WhenTheUndoFailsToo() {
                givenCamille();
                RuntimeException databaseDown = new IllegalStateException("database down");
                RuntimeException providerDown = new IllegalStateException("identity provider down");
                when(identityProvider.updateProfile(eq("kc-camille"), any()))
                                .thenReturn(Mono.empty())
                                .thenReturn(Mono.error(providerDown));
                when(userRepository.updateProfile(any(), any())).thenReturn(Mono.error(databaseDown));

                StepVerifier.create(userService.updateProfile(USER_ID, new ProfileUpdate("camille_d", "Cam", "Dupont")))
                                .expectErrorMatches(error -> error == databaseDown
                                                && error.getSuppressed().length == 1
                                                && error.getSuppressed()[0] == providerDown)
                                .verify(Duration.ofSeconds(10));
        }

        @Test
        void updateProfile_ShouldError_WhenTheUserDoesNotExist() {
                when(userRepository.getBusinessUserByUserId(USER_ID)).thenReturn(Mono.empty());
                when(userRepository.findSubjectIdByUserId(USER_ID)).thenReturn(Mono.empty());
                when(userRepository.findAccountState(USER_ID)).thenReturn(Mono.empty());

                StepVerifier.create(userService.updateProfile(USER_ID, new ProfileUpdate("camille", "Cam", "Dupont")))
                                .expectErrorMatches(error -> error instanceof BusinessException business
                                                && business.getErrorCode() == BusinessErrorCode.USER_NOT_FOUND)
                                .verify(Duration.ofSeconds(10));

                verify(identityProvider, never()).updateProfile(any(), any());
                verify(userRepository, never()).updateProfile(any(), any());
        }

        // --- pictures --------------------------------------------------------

        private static final byte[] UPLOAD = {1, 2, 3};
        private static final String OLD_KEY = "avatars/" + USER_ID + "/old.jpg";
        private static final String SIGNED = "https://media/signed";

        private BusinessUser camilleWithPicture(String reference) {
                return new BusinessUser(USER_ID, "camille", 4321, "Camille", "Durand", reference, JOINED);
        }

        /** wely-media accepts the upload and answers with the key it was given. */
        private void givenTheUploadIsStored() {
                when(mediaStore.store(anyString(), eq(UPLOAD), eq(MediaProfile.AVATAR)))
                                .thenAnswer(call -> Mono.just(new StoredMedia(call.getArgument(0), 512, 512)));
        }

        /** wely-media signs whatever key it is asked for. */
        private void givenKeysAreSigned() {
                when(mediaStore.urls(anyCollection())).thenAnswer(call -> {
                        Collection<String> keys = call.getArgument(0);
                        return Mono.just(keys.stream().collect(Collectors.toMap(key -> key, key -> SIGNED)));
                });
        }

        @Test
        void updatePicture_ShouldStoreUnderAFreshKey_PointTheUserAtIt_ThenRemoveThePreviousUpload() {
                when(userRepository.getBusinessUserByUserId(USER_ID)).thenReturn(Mono.just(camilleWithPicture(OLD_KEY)));
                givenTheUploadIsStored();
                when(userRepository.updatePicture(eq(USER_ID), anyString())).thenReturn(Mono.just(CAMILLE));
                when(mediaStore.delete(OLD_KEY)).thenReturn(Mono.empty());
                givenKeysAreSigned();

                StepVerifier.create(userService.updatePicture(USER_ID, UPLOAD))
                                .expectNext(SIGNED)
                                .expectComplete().verify(Duration.ofSeconds(10));

                ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
                InOrder order = inOrder(mediaStore, userRepository);
                order.verify(mediaStore).store(key.capture(), eq(UPLOAD), eq(MediaProfile.AVATAR));
                order.verify(userRepository).updatePicture(USER_ID, key.getValue());
                order.verify(mediaStore).delete(OLD_KEY);
                order.verify(mediaStore).urls(List.of(key.getValue()));
                org.assertj.core.api.Assertions.assertThat(key.getValue())
                                .matches("avatars/" + USER_ID + "/[0-9a-f-]{36}\\.jpg");
        }

        @Test
        void updatePicture_ShouldLeaveAPathTheWebAppServes_WhenReplacingIt() {
                when(userRepository.getBusinessUserByUserId(USER_ID))
                                .thenReturn(Mono.just(camilleWithPicture("/demo/avatars/camille.svg")));
                givenTheUploadIsStored();
                when(userRepository.updatePicture(eq(USER_ID), anyString())).thenReturn(Mono.just(CAMILLE));
                givenKeysAreSigned();

                StepVerifier.create(userService.updatePicture(USER_ID, UPLOAD)).expectNext(SIGNED).expectComplete().verify(Duration.ofSeconds(10));

                verify(mediaStore, never()).delete(anyString());
        }

        @Test
        void updatePicture_ShouldRemoveTheNewFile_WhenTheDatabaseRefuses() {
                when(userRepository.getBusinessUserByUserId(USER_ID)).thenReturn(Mono.just(camilleWithPicture(OLD_KEY)));
                givenTheUploadIsStored();
                when(userRepository.updatePicture(eq(USER_ID), anyString()))
                                .thenReturn(Mono.error(new IllegalStateException("database down")));
                when(mediaStore.delete(anyString())).thenReturn(Mono.empty());

                StepVerifier.create(userService.updatePicture(USER_ID, UPLOAD))
                                .expectError(IllegalStateException.class)
                                .verify(Duration.ofSeconds(10));

                ArgumentCaptor<String> stored = ArgumentCaptor.forClass(String.class);
                verify(mediaStore).store(stored.capture(), eq(UPLOAD), eq(MediaProfile.AVATAR));
                verify(mediaStore).delete(stored.getValue());
                verify(mediaStore, never()).delete(OLD_KEY);
                verify(mediaStore, never()).urls(anyCollection());
        }

        @Test
        void updatePicture_ShouldKeepTheDatabaseError_WhenTheCleanupFailsToo() {
                when(userRepository.getBusinessUserByUserId(USER_ID)).thenReturn(Mono.just(camilleWithPicture(null)));
                givenTheUploadIsStored();
                when(userRepository.updatePicture(eq(USER_ID), anyString()))
                                .thenReturn(Mono.error(new IllegalStateException("database down")));
                when(mediaStore.delete(anyString()))
                                .thenReturn(Mono.error(new TechnicalException(TechnicalErrorCode.MEDIA_ERROR)));

                StepVerifier.create(userService.updatePicture(USER_ID, UPLOAD))
                                .expectError(IllegalStateException.class)
                                .verify(Duration.ofSeconds(10));
        }

        @Test
        void updatePicture_ShouldPointNobodyAtIt_WhenTheUploadIsNotAnImage() {
                when(userRepository.getBusinessUserByUserId(USER_ID)).thenReturn(Mono.just(camilleWithPicture(OLD_KEY)));
                when(mediaStore.store(anyString(), eq(UPLOAD), eq(MediaProfile.AVATAR)))
                                .thenReturn(Mono.error(new BusinessException(BusinessErrorCode.PICTURE_INVALID)));

                StepVerifier.create(userService.updatePicture(USER_ID, UPLOAD))
                                .expectErrorMatches(error -> error instanceof BusinessException business
                                                && business.getErrorCode() == BusinessErrorCode.PICTURE_INVALID)
                                .verify(Duration.ofSeconds(10));

                verify(userRepository, never()).updatePicture(any(), anyString());
                verify(mediaStore, never()).delete(anyString());
        }

        @Test
        void updatePicture_ShouldFail_WhenTheMediaStoreIsDown() {
                when(userRepository.getBusinessUserByUserId(USER_ID)).thenReturn(Mono.just(camilleWithPicture(null)));
                when(mediaStore.store(anyString(), eq(UPLOAD), eq(MediaProfile.AVATAR)))
                                .thenReturn(Mono.error(new TechnicalException(TechnicalErrorCode.MEDIA_ERROR)));

                StepVerifier.create(userService.updatePicture(USER_ID, UPLOAD))
                                .expectErrorMatches(error -> error instanceof TechnicalException technical
                                                && technical.getErrorCode() == TechnicalErrorCode.MEDIA_ERROR)
                                .verify(Duration.ofSeconds(10));

                verify(userRepository, never()).updatePicture(any(), anyString());
        }

        @Test
        void updatePicture_ShouldStillSucceed_WhenThePreviousFileCannotBeRemoved() {
                when(userRepository.getBusinessUserByUserId(USER_ID)).thenReturn(Mono.just(camilleWithPicture(OLD_KEY)));
                givenTheUploadIsStored();
                when(userRepository.updatePicture(eq(USER_ID), anyString())).thenReturn(Mono.just(CAMILLE));
                when(mediaStore.delete(OLD_KEY)).thenReturn(Mono.error(new IllegalStateException("storage down")));
                givenKeysAreSigned();

                StepVerifier.create(userService.updatePicture(USER_ID, UPLOAD))
                                .expectNext(SIGNED)
                                .expectComplete().verify(Duration.ofSeconds(10));
        }

        @Test
        void updatePicture_ShouldCompleteEmpty_WhenTheSavedPictureCannotBeSigned() {
                when(userRepository.getBusinessUserByUserId(USER_ID)).thenReturn(Mono.just(camilleWithPicture(null)));
                givenTheUploadIsStored();
                when(userRepository.updatePicture(eq(USER_ID), anyString())).thenReturn(Mono.just(CAMILLE));
                when(mediaStore.urls(anyCollection()))
                                .thenReturn(Mono.error(new TechnicalException(TechnicalErrorCode.MEDIA_ERROR)));

                StepVerifier.create(userService.updatePicture(USER_ID, UPLOAD)).expectComplete().verify(Duration.ofSeconds(10));

                verify(userRepository).updatePicture(eq(USER_ID), anyString());
                verify(mediaStore, never()).delete(anyString());
        }

        @Test
        void updatePicture_ShouldCompleteEmpty_WhenTheStoreLeavesTheKeyUnsigned() {
                when(userRepository.getBusinessUserByUserId(USER_ID)).thenReturn(Mono.just(camilleWithPicture(null)));
                givenTheUploadIsStored();
                when(userRepository.updatePicture(eq(USER_ID), anyString())).thenReturn(Mono.just(CAMILLE));
                when(mediaStore.urls(anyCollection())).thenReturn(Mono.just(Map.of()));

                StepVerifier.create(userService.updatePicture(USER_ID, UPLOAD)).expectComplete().verify(Duration.ofSeconds(10));
        }

        @Test
        void updatePicture_ShouldError_WhenTheUserDoesNotExist() {
                when(userRepository.getBusinessUserByUserId(USER_ID)).thenReturn(Mono.empty());
                when(userRepository.findAccountState(USER_ID)).thenReturn(Mono.empty());

                StepVerifier.create(userService.updatePicture(USER_ID, UPLOAD))
                                .expectErrorMatches(error -> error instanceof BusinessException business
                                                && business.getErrorCode() == BusinessErrorCode.USER_NOT_FOUND)
                                .verify(Duration.ofSeconds(10));

                verifyNoInteractions(mediaStore);
        }

        @Test
        void pictureUrls_ShouldSignUploadsInOneCallAndPassPathsThrough() {
                UUID lea = UUID.fromString("22222222-2222-2222-2222-222222222222");
                UUID hugo = UUID.fromString("33333333-3333-3333-3333-333333333333");
                when(userRepository.findPictures(Set.of(USER_ID, lea, hugo))).thenReturn(Flux.just(
                                new UserPicture(USER_ID, "avatars/" + USER_ID + "/a.jpg"),
                                new UserPicture(hugo, "avatars/" + hugo + "/h.jpg"),
                                new UserPicture(lea, "/demo/avatars/lea.svg")));
                givenKeysAreSigned();

                StepVerifier.create(userService.pictureUrls(Set.of(USER_ID, lea, hugo)))
                                .expectNext(Map.of(USER_ID, SIGNED, hugo, SIGNED, lea, "/demo/avatars/lea.svg"))
                                .expectComplete().verify(Duration.ofSeconds(10));

                verify(mediaStore, times(1))
                                .urls(List.of("avatars/" + USER_ID + "/a.jpg", "avatars/" + hugo + "/h.jpg"));
        }

        @Test
        void pictureUrls_ShouldNotCallTheMediaStore_WhenNoPictureIsAnUpload() {
                UUID lea = UUID.fromString("22222222-2222-2222-2222-222222222222");
                when(userRepository.findPictures(Set.of(lea)))
                                .thenReturn(Flux.just(new UserPicture(lea, "/demo/avatars/lea.svg")));

                StepVerifier.create(userService.pictureUrls(Set.of(lea)))
                                .expectNext(Map.of(lea, "/demo/avatars/lea.svg"))
                                .expectComplete().verify(Duration.ofSeconds(10));

                verifyNoInteractions(mediaStore);
        }

        @Test
        void pictureUrls_ShouldAnswerAnEmptyMap_WhenNobodyHasAPicture() {
                when(userRepository.findPictures(Set.of(USER_ID))).thenReturn(Flux.empty());

                StepVerifier.create(userService.pictureUrls(Set.of(USER_ID)))
                                .expectNext(Map.of())
                                .expectComplete().verify(Duration.ofSeconds(10));
        }

        @Test
        void pictureUrls_ShouldLeaveUploadsOutButKeepPaths_WhenTheMediaStoreIsDown() {
                UUID lea = UUID.fromString("22222222-2222-2222-2222-222222222222");
                when(userRepository.findPictures(Set.of(USER_ID, lea))).thenReturn(Flux.just(
                                new UserPicture(USER_ID, "avatars/" + USER_ID + "/a.jpg"),
                                new UserPicture(lea, "/demo/avatars/lea.svg")));
                when(mediaStore.urls(anyCollection()))
                                .thenReturn(Mono.error(new TechnicalException(TechnicalErrorCode.MEDIA_ERROR)));

                StepVerifier.create(userService.pictureUrls(Set.of(USER_ID, lea)))
                                .expectNext(Map.of(lea, "/demo/avatars/lea.svg"))
                                .expectComplete().verify(Duration.ofSeconds(10));
        }

        @Test
        void pictureUrls_ShouldLeaveAnUploadOut_WhenTheStoreDidNotSignIt() {
                when(userRepository.findPictures(Set.of(USER_ID)))
                                .thenReturn(Flux.just(new UserPicture(USER_ID, "avatars/" + USER_ID + "/a.jpg")));
                when(mediaStore.urls(anyCollection())).thenReturn(Mono.just(Map.of()));

                StepVerifier.create(userService.pictureUrls(Set.of(USER_ID)))
                                .expectNext(Map.of())
                                .expectComplete().verify(Duration.ofSeconds(10));
        }

        @Test
        void pictureUrls_ShouldFail_WhenTheDatabaseFails() {
                when(userRepository.findPictures(Set.of(USER_ID)))
                                .thenReturn(Flux.error(new TechnicalException(TechnicalErrorCode.DATABASE_ERROR)));

                StepVerifier.create(userService.pictureUrls(Set.of(USER_ID)))
                                .expectError(TechnicalException.class)
                                .verify(Duration.ofSeconds(10));
        }

        @Test
        void about_shouldReturnWhatTheUserWrote() {
                UUID userId = UUID.randomUUID();
                UserAbout about = new UserAbout("Partante", List.of("Rando"));
                when(userRepository.findAbout(userId)).thenReturn(Mono.just(about));

                StepVerifier.create(userService.about(userId)).expectNext(about).expectComplete().verify(Duration.ofSeconds(10));
        }

        @Test
        void about_shouldBeEmpty_whenNothingWasWritten() {
                UUID userId = UUID.randomUUID();
                when(userRepository.findAbout(userId)).thenReturn(Mono.empty());
                when(userRepository.findAccountState(userId)).thenReturn(Mono.just(AccountState.ACTIVE));

                StepVerifier.create(userService.about(userId)).expectNext(UserAbout.EMPTY).expectComplete().verify(Duration.ofSeconds(10));
        }

        @Test
        void updateAbout_shouldStoreIt_withoutTouchingTheIdentityProvider() {
                UUID userId = UUID.randomUUID();
                UserAbout about = new UserAbout("Partante", List.of("Rando"));
                when(userRepository.updateAbout(userId, about)).thenReturn(Mono.just(about));

                StepVerifier.create(userService.updateAbout(userId, about)).expectNext(about).expectComplete().verify(Duration.ofSeconds(10));
                verifyNoInteractions(identityProvider);
        }

        @Test
        void updateAbout_shouldFail_forAnUnknownUser() {
                UUID userId = UUID.randomUUID();
                when(userRepository.updateAbout(any(), any()))
                                .thenReturn(Mono.error(new BusinessException(BusinessErrorCode.USER_NOT_FOUND)));
                when(userRepository.findAccountState(userId)).thenReturn(Mono.empty());

                StepVerifier.create(userService.updateAbout(userId, UserAbout.EMPTY))
                                .expectErrorMatches(error -> isBusiness(error, BusinessErrorCode.USER_NOT_FOUND))
                                .verify(Duration.ofSeconds(10));
        }

        private static final UUID VIEWER = UUID.fromString("11111111-1111-1111-1111-111111111111");
        private static final UUID FRIEND = UUID.fromString("22222222-2222-2222-2222-222222222222");
        private static final LocalDateTime FRIEND_JOINED = LocalDateTime.of(2026, 1, 15, 10, 30);
        private static final BusinessUser FRIEND_USER =
                        new BusinessUser(FRIEND, "lea", 4242, "Léa", "Martin", "avatars/x.jpg", FRIEND_JOINED);

        @Test
        void friendProfile_shouldReturnTheProfileAndAbout_whenTheyAreFriends() {
                when(userRepository.getBusinessUserByUserId(FRIEND)).thenReturn(Mono.just(FRIEND_USER));
                when(friendshipChecker.areFriends(VIEWER.toString(), FRIEND.toString())).thenReturn(Mono.just(true));
                when(userRepository.findAbout(FRIEND)).thenReturn(Mono.just(new UserAbout("Partante", List.of("Rando"))));

                StepVerifier.create(userService.friendProfile(VIEWER, FRIEND))
                                .expectNext(new FriendProfile(FRIEND, "lea", 4242, "Léa", "Martin", FRIEND_JOINED,
                                                "Partante", List.of("Rando"), null))
                                .expectComplete().verify(Duration.ofSeconds(10));
        }

        @Test
        void friendProfile_shouldGiveEmptyAbout_whenTheFriendWroteNothing() {
                when(userRepository.getBusinessUserByUserId(FRIEND)).thenReturn(Mono.just(FRIEND_USER));
                when(friendshipChecker.areFriends(VIEWER.toString(), FRIEND.toString())).thenReturn(Mono.just(true));
                when(userRepository.findAbout(FRIEND)).thenReturn(Mono.empty());
                when(userRepository.findAccountState(FRIEND)).thenReturn(Mono.just(AccountState.ACTIVE));

                StepVerifier.create(userService.friendProfile(VIEWER, FRIEND))
                                .expectNextMatches(profile -> profile.bio() == null && profile.interests().isEmpty())
                                .expectComplete().verify(Duration.ofSeconds(10));
        }

        @Test
        void friendProfile_shouldRefuseWithNotAFriend_whenTheyAreNotFriends() {
                when(userRepository.getBusinessUserByUserId(FRIEND)).thenReturn(Mono.just(FRIEND_USER));
                when(friendshipChecker.areFriends(VIEWER.toString(), FRIEND.toString())).thenReturn(Mono.just(false));

                StepVerifier.create(userService.friendProfile(VIEWER, FRIEND))
                                .expectErrorMatches(error -> error instanceof BusinessException e
                                                && e.getErrorCode() == BusinessErrorCode.NOT_A_FRIEND)
                                .verify(Duration.ofSeconds(10));
                verify(userRepository, never()).findAbout(any());
        }

        @Test
        void friendProfile_shouldReturnTheirOwnProfile_withoutAskingSocial_whenViewerIsTheUser() {
                when(userRepository.getBusinessUserByUserId(FRIEND)).thenReturn(Mono.just(FRIEND_USER));
                when(userRepository.findAbout(FRIEND)).thenReturn(Mono.empty());
                when(userRepository.findAccountState(FRIEND)).thenReturn(Mono.just(AccountState.ACTIVE));

                StepVerifier.create(userService.friendProfile(FRIEND, FRIEND))
                                .expectNextMatches(profile -> profile.id().equals(FRIEND))
                                .expectComplete().verify(Duration.ofSeconds(10));
                verifyNoInteractions(friendshipChecker);
        }

        @Test
        void friendProfile_shouldFailWithUserNotFound_whenTheUserIsUnknown() {
                when(userRepository.getBusinessUserByUserId(FRIEND)).thenReturn(Mono.empty());
                when(userRepository.findAccountState(FRIEND)).thenReturn(Mono.empty());

                StepVerifier.create(userService.friendProfile(VIEWER, FRIEND))
                                .expectErrorMatches(error -> error instanceof BusinessException e
                                                && e.getErrorCode() == BusinessErrorCode.USER_NOT_FOUND)
                                .verify(Duration.ofSeconds(10));
                verifyNoInteractions(friendshipChecker);
        }

        @Test
        void friendProfile_shouldPropagateTheOutage_whenSocialIsUnavailable() {
                when(userRepository.getBusinessUserByUserId(FRIEND)).thenReturn(Mono.just(FRIEND_USER));
                when(friendshipChecker.areFriends(VIEWER.toString(), FRIEND.toString()))
                                .thenReturn(Mono.error(new TechnicalException(TechnicalErrorCode.SOCIAL_ERROR)));

                StepVerifier.create(userService.friendProfile(VIEWER, FRIEND))
                                .expectErrorMatches(error -> error instanceof TechnicalException e
                                                && e.getErrorCode() == TechnicalErrorCode.SOCIAL_ERROR)
                                .verify(Duration.ofSeconds(10));
                verify(userRepository, never()).findAbout(any());
        }

        @Test
        void preferences_shouldReturnWhatTheUserChose() {
                UUID userId = UUID.randomUUID();
                when(userRepository.findPreferences(userId)).thenReturn(Mono.just(new UserPreferences(Theme.DARK)));

                StepVerifier.create(userService.preferences(userId))
                                .expectNext(new UserPreferences(Theme.DARK))
                                .expectComplete().verify(Duration.ofSeconds(10));
        }

        @Test
        void preferences_shouldBeTheDefaults_whenNothingIsStored() {
                UUID userId = UUID.randomUUID();
                when(userRepository.findPreferences(userId)).thenReturn(Mono.empty());
                when(userRepository.findAccountState(userId)).thenReturn(Mono.just(AccountState.ACTIVE));

                StepVerifier.create(userService.preferences(userId)).expectNext(UserPreferences.DEFAULT).expectComplete().verify(Duration.ofSeconds(10));
        }

        @Test
        void updatePreferences_shouldStoreThem_withoutTouchingTheIdentityProvider() {
                UUID userId = UUID.randomUUID();
                UserPreferences dark = new UserPreferences(Theme.DARK);
                when(userRepository.updatePreferences(userId, dark)).thenReturn(Mono.just(dark));

                StepVerifier.create(userService.updatePreferences(userId, dark)).expectNext(dark).expectComplete().verify(Duration.ofSeconds(10));
                verifyNoInteractions(identityProvider);
        }

        @Test
        void updatePreferences_shouldFail_forAnUnknownUser() {
                UUID userId = UUID.randomUUID();
                when(userRepository.updatePreferences(any(), any()))
                                .thenReturn(Mono.error(new BusinessException(BusinessErrorCode.USER_NOT_FOUND)));
                when(userRepository.findAccountState(userId)).thenReturn(Mono.empty());

                StepVerifier.create(userService.updatePreferences(userId, UserPreferences.DEFAULT))
                                .expectErrorMatches(error -> isBusiness(error, BusinessErrorCode.USER_NOT_FOUND))
                                .verify(Duration.ofSeconds(10));
        }

        // --- deleted accounts ------------------------------------------------

        private static boolean isBusiness(Throwable error, BusinessErrorCode code) {
                return error instanceof BusinessException business && business.getErrorCode() == code;
        }

        private void givenATombstone(UUID userId) {
                when(userRepository.findAccountState(userId)).thenReturn(Mono.just(AccountState.DELETED));
        }

        @Test
        void readProfile_shouldFailWithUserDeleted_whenTheUserIsATombstone() {
                when(userRepository.getBusinessUserByUserId(USER_ID)).thenReturn(Mono.empty());
                givenATombstone(USER_ID);

                StepVerifier.create(userService.readProfile(USER_ID))
                                .expectErrorMatches(error -> isBusiness(error, BusinessErrorCode.USER_DELETED))
                                .verify(Duration.ofSeconds(10));
        }

        @Test
        void readProfile_shouldFailWithUserNotFound_whenTheUserIsUnknown() {
                when(userRepository.getBusinessUserByUserId(USER_ID)).thenReturn(Mono.empty());
                when(userRepository.findAccountState(USER_ID)).thenReturn(Mono.empty());

                StepVerifier.create(userService.readProfile(USER_ID))
                                .expectErrorMatches(error -> isBusiness(error, BusinessErrorCode.USER_NOT_FOUND))
                                .verify(Duration.ofSeconds(10));
        }

        @Test
        void updateProfile_shouldFailWithUserDeletedWithoutCallingTheIdentityProvider_whenTheUserIsATombstone() {
                when(userRepository.getBusinessUserByUserId(USER_ID)).thenReturn(Mono.empty());
                when(userRepository.findSubjectIdByUserId(USER_ID)).thenReturn(Mono.empty());
                givenATombstone(USER_ID);

                StepVerifier.create(userService.updateProfile(USER_ID, new ProfileUpdate("camille", "Cam", "Dupont")))
                                .expectErrorMatches(error -> isBusiness(error, BusinessErrorCode.USER_DELETED))
                                .verify(Duration.ofSeconds(10));

                verifyNoInteractions(identityProvider);
                verify(userRepository, never()).updateProfile(any(), any());
        }

        @Test
        void updatePicture_shouldFailWithUserDeletedWithoutStoring_whenTheUserIsATombstone() {
                when(userRepository.getBusinessUserByUserId(USER_ID)).thenReturn(Mono.empty());
                givenATombstone(USER_ID);

                StepVerifier.create(userService.updatePicture(USER_ID, UPLOAD))
                                .expectErrorMatches(error -> isBusiness(error, BusinessErrorCode.USER_DELETED))
                                .verify(Duration.ofSeconds(10));

                verifyNoInteractions(mediaStore);
        }

        @Test
        void about_shouldFailWithUserDeleted_whenTheUserIsATombstone() {
                when(userRepository.findAbout(USER_ID)).thenReturn(Mono.empty());
                givenATombstone(USER_ID);

                StepVerifier.create(userService.about(USER_ID))
                                .expectErrorMatches(error -> isBusiness(error, BusinessErrorCode.USER_DELETED))
                                .verify(Duration.ofSeconds(10));
        }

        @Test
        void about_shouldStillBeEmpty_whenTheUserIsUnknown() {
                when(userRepository.findAbout(USER_ID)).thenReturn(Mono.empty());
                when(userRepository.findAccountState(USER_ID)).thenReturn(Mono.empty());

                StepVerifier.create(userService.about(USER_ID)).expectNext(UserAbout.EMPTY).expectComplete().verify(Duration.ofSeconds(10));
        }

        @Test
        void updateAbout_shouldFailWithUserDeleted_whenTheUserIsATombstone() {
                when(userRepository.updateAbout(any(), any()))
                                .thenReturn(Mono.error(new BusinessException(BusinessErrorCode.USER_NOT_FOUND)));
                givenATombstone(USER_ID);

                StepVerifier.create(userService.updateAbout(USER_ID, UserAbout.EMPTY))
                                .expectErrorMatches(error -> isBusiness(error, BusinessErrorCode.USER_DELETED))
                                .verify(Duration.ofSeconds(10));
        }

        @Test
        void updateAbout_shouldNotAskForTheAccountState_whenTheFailureIsNotAMissingUser() {
                when(userRepository.updateAbout(any(), any()))
                                .thenReturn(Mono.error(new TechnicalException(TechnicalErrorCode.DATABASE_ERROR)));

                StepVerifier.create(userService.updateAbout(USER_ID, UserAbout.EMPTY))
                                .expectError(TechnicalException.class)
                                .verify(Duration.ofSeconds(10));

                verify(userRepository, never()).findAccountState(any());
        }

        @Test
        void preferences_shouldFailWithUserDeleted_whenTheUserIsATombstone() {
                when(userRepository.findPreferences(USER_ID)).thenReturn(Mono.empty());
                givenATombstone(USER_ID);

                StepVerifier.create(userService.preferences(USER_ID))
                                .expectErrorMatches(error -> isBusiness(error, BusinessErrorCode.USER_DELETED))
                                .verify(Duration.ofSeconds(10));
        }

        @Test
        void preferences_shouldStillBeTheDefaults_whenTheUserIsUnknown() {
                when(userRepository.findPreferences(USER_ID)).thenReturn(Mono.empty());
                when(userRepository.findAccountState(USER_ID)).thenReturn(Mono.empty());

                StepVerifier.create(userService.preferences(USER_ID)).expectNext(UserPreferences.DEFAULT).expectComplete().verify(Duration.ofSeconds(10));
        }

        @Test
        void updatePreferences_shouldFailWithUserDeleted_whenTheUserIsATombstone() {
                when(userRepository.updatePreferences(any(), any()))
                                .thenReturn(Mono.error(new BusinessException(BusinessErrorCode.USER_NOT_FOUND)));
                givenATombstone(USER_ID);

                StepVerifier.create(userService.updatePreferences(USER_ID, UserPreferences.DEFAULT))
                                .expectErrorMatches(error -> isBusiness(error, BusinessErrorCode.USER_DELETED))
                                .verify(Duration.ofSeconds(10));
        }

        @Test
        void friendProfile_shouldFailWithUserDeletedBeforeAskingSocial_whenTheUserIsATombstone() {
                when(userRepository.getBusinessUserByUserId(FRIEND)).thenReturn(Mono.empty());
                givenATombstone(FRIEND);

                StepVerifier.create(userService.friendProfile(VIEWER, FRIEND))
                                .expectErrorMatches(error -> isBusiness(error, BusinessErrorCode.USER_DELETED))
                                .verify(Duration.ofSeconds(10));

                verifyNoInteractions(friendshipChecker);
        }

        @Test
        void provisionUser_shouldDrawAnotherHashtag_whenTheDrawnHandleIsTakenOrRetired() {
                // existsByUserNameAndHashtag answers true for a retired handle too: provisioning draws again.
                when(userRepository.findIdBySubjectId("kc-new")).thenReturn(Mono.empty());
                when(userRepository.existsByUserNameAndHashtag(eq("lea"), anyInt()))
                                .thenReturn(Mono.just(true))
                                .thenReturn(Mono.just(true))
                                .thenReturn(Mono.just(false));
                when(userRepository.save(any(BusinessUser.class), eq("kc-new"))).thenAnswer(call -> Mono.just(
                                new BusinessUser(USER_ID, "lea", ((BusinessUser) call.getArgument(0)).hashtag(),
                                                "Léa", "Martin", null, JOINED)));

                StepVerifier.create(userService.provisionUser("kc-new", new IdentityUser("lea", "Léa", "Martin")))
                                .expectNext(USER_ID)
                                .expectComplete().verify(Duration.ofSeconds(10));

                verify(userRepository, times(3)).existsByUserNameAndHashtag(eq("lea"), anyInt());
        }

        @Test
        void updateProfile_shouldPassTheCurrentUsername_soTheRepositoryChecksRetirementOnlyOnARename() {
                givenCamille();
                when(identityProvider.updateProfile(any(), any())).thenReturn(Mono.empty());
                when(userRepository.updateProfile(any(), any())).thenAnswer(call -> Mono.just(call.getArgument(0)));

                StepVerifier.create(userService.updateProfile(USER_ID, new ProfileUpdate("camille", "Cam", "Dupont")))
                                .expectNextCount(1)
                                .expectComplete().verify(Duration.ofSeconds(10));

                verify(userRepository).updateProfile(any(), eq("camille"));
        }

        // --- completeRegistration --------------------------------------------

        private static final Clock TODAY = Clock.fixed(Instant.parse("2026-10-18T09:00:00Z"), ZoneOffset.UTC);
        private static final Registration REGISTRATION = new Registration(LocalDate.of(1995, 3, 12), "Léa", "Martin");
        /** Provisioned from an Auth0 signup, which asks no names. */
        private static final BusinessUser NAMELESS = new BusinessUser(USER_ID, "lea", 1, null, null, null, null);

        private UserService serviceOn18October() {
                return new UserService(userRepository, identityProvider, mediaStore, friendshipChecker, TODAY, usersActivity);
        }

        @Test
        void completeRegistration_shouldStoreTheRegistrationAsCompleted_whenItFillsEveryMissingField() {
                BusinessUser registered = new BusinessUser(USER_ID, "lea", 1, "Léa", "Martin", null, null,
                                REGISTRATION.birthDate(), true);
                when(userRepository.getBusinessUserByUserId(USER_ID)).thenReturn(Mono.just(NAMELESS));
                when(userRepository.completeRegistration(USER_ID, REGISTRATION, true)).thenReturn(Mono.just(registered));

                StepVerifier.create(serviceOn18October().completeRegistration(USER_ID, REGISTRATION))
                                .expectNext(registered)
                                .expectComplete().verify(Duration.ofSeconds(10));
        }

        @Test
        void completeRegistration_shouldStoreAPartialPassAsNotCompleted_whenAFieldStaysMissing() {
                Registration birthDateOnly = new Registration(REGISTRATION.birthDate(), null, null);
                when(userRepository.getBusinessUserByUserId(USER_ID)).thenReturn(Mono.just(NAMELESS));
                when(userRepository.completeRegistration(USER_ID, birthDateOnly, false)).thenReturn(Mono.just(NAMELESS));

                StepVerifier.create(serviceOn18October().completeRegistration(USER_ID, birthDateOnly))
                                .expectNext(NAMELESS)
                                .expectComplete().verify(Duration.ofSeconds(10));
        }

        @Test
        void completeRegistration_shouldStoreTrimmedNames_whenTheyComeWithSpaces() {
                when(userRepository.getBusinessUserByUserId(USER_ID)).thenReturn(Mono.just(NAMELESS));
                when(userRepository.completeRegistration(eq(USER_ID), any(), anyBoolean())).thenReturn(Mono.just(NAMELESS));

                StepVerifier.create(serviceOn18October().completeRegistration(USER_ID,
                                                new Registration(REGISTRATION.birthDate(), " Léa ", " Martin ")))
                                .expectNextCount(1)
                                .expectComplete().verify(Duration.ofSeconds(10));

                verify(userRepository).completeRegistration(USER_ID, REGISTRATION, true);
        }

        @Test
        void completeRegistration_shouldChangeTheBirthDateAlone_whenTheUserAlreadyHasNames() {
                Registration birthDateOnly = new Registration(LocalDate.of(1996, 4, 2), null, null);
                when(userRepository.getBusinessUserByUserId(USER_ID)).thenReturn(Mono.just(CAMILLE));
                when(userRepository.completeRegistration(USER_ID, birthDateOnly, true)).thenReturn(Mono.just(CAMILLE));

                StepVerifier.create(serviceOn18October().completeRegistration(USER_ID, birthDateOnly))
                                .expectNext(CAMILLE)
                                .expectComplete().verify(Duration.ofSeconds(10));
        }

        @Test
        void completeRegistration_shouldRefuseWithRegistrationEmpty_withoutReadingTheUser() {
                StepVerifier.create(serviceOn18October().completeRegistration(USER_ID, new Registration(null, null, null)))
                                .expectErrorMatches(error -> isBusiness(error, BusinessErrorCode.REGISTRATION_EMPTY))
                                .verify(Duration.ofSeconds(10));

                verify(userRepository, never()).getBusinessUserByUserId(any());
                verify(userRepository, never()).completeRegistration(any(), any(), anyBoolean());
        }

        @Test
        void completeRegistration_shouldRefuseWithNamesInvalid_whenASentNameIsBlank() {
                StepVerifier.create(serviceOn18October().completeRegistration(USER_ID, new Registration(null, " ", "Martin")))
                                .expectErrorMatches(error -> isBusiness(error, BusinessErrorCode.NAMES_INVALID))
                                .verify(Duration.ofSeconds(10));

                verify(userRepository, never()).completeRegistration(any(), any(), anyBoolean());
        }

        @Test
        void completeRegistration_shouldRefuseWithBirthDateInvalid_whenTheUserIsTooYoung() {
                Registration tooYoung = new Registration(LocalDate.of(2015, 1, 1), "Léa", "Martin");

                StepVerifier.create(serviceOn18October().completeRegistration(USER_ID, tooYoung))
                                .expectErrorMatches(error -> isBusiness(error, BusinessErrorCode.BIRTH_DATE_INVALID))
                                .verify(Duration.ofSeconds(10));

                verify(userRepository, never()).completeRegistration(any(), any(), anyBoolean());
        }

        @Test
        void completeRegistration_shouldAnswerUserDeleted_whenTheUserIsATombstone() {
                when(userRepository.getBusinessUserByUserId(USER_ID)).thenReturn(Mono.empty());
                when(userRepository.findAccountState(USER_ID)).thenReturn(Mono.just(AccountState.DELETED));

                StepVerifier.create(serviceOn18October().completeRegistration(USER_ID, REGISTRATION))
                                .expectErrorMatches(error -> isBusiness(error, BusinessErrorCode.USER_DELETED))
                                .verify(Duration.ofSeconds(10));

                verify(userRepository, never()).completeRegistration(any(), any(), anyBoolean());
        }

        @Test
        void completeRegistration_shouldAnswerUserNotFound_whenTheUserIsUnknown() {
                when(userRepository.getBusinessUserByUserId(USER_ID)).thenReturn(Mono.empty());
                when(userRepository.findAccountState(USER_ID)).thenReturn(Mono.empty());

                StepVerifier.create(serviceOn18October().completeRegistration(USER_ID, REGISTRATION))
                                .expectErrorMatches(error -> isBusiness(error, BusinessErrorCode.USER_NOT_FOUND))
                                .verify(Duration.ofSeconds(10));
        }

        @Test
        void completeRegistration_shouldAnswerUserDeleted_whenTheUserIsDeletedBetweenTheReadAndTheWrite() {
                when(userRepository.getBusinessUserByUserId(USER_ID)).thenReturn(Mono.just(NAMELESS));
                when(userRepository.completeRegistration(USER_ID, REGISTRATION, true))
                                .thenReturn(Mono.error(new BusinessException(BusinessErrorCode.USER_NOT_FOUND)));
                when(userRepository.findAccountState(USER_ID)).thenReturn(Mono.just(AccountState.DELETED));

                StepVerifier.create(serviceOn18October().completeRegistration(USER_ID, REGISTRATION))
                                .expectErrorMatches(error -> isBusiness(error, BusinessErrorCode.USER_DELETED))
                                .verify(Duration.ofSeconds(10));
        }

        @Test
        void friendProfile_shouldCarryTheBirthdayWithoutTheYear_whenTheFriendHasABirthDate() {
                BusinessUser withBirthday = new BusinessUser(FRIEND, "lea", 4242, "Léa", "Martin", null, FRIEND_JOINED,
                                LocalDate.of(1995, 3, 12), true);
                when(userRepository.getBusinessUserByUserId(FRIEND)).thenReturn(Mono.just(withBirthday));
                when(friendshipChecker.areFriends(VIEWER.toString(), FRIEND.toString())).thenReturn(Mono.just(true));
                when(userRepository.findAbout(FRIEND)).thenReturn(Mono.just(UserAbout.EMPTY));

                StepVerifier.create(userService.friendProfile(VIEWER, FRIEND))
                                .expectNextMatches(profile -> MonthDay.of(3, 12).equals(profile.birthday()))
                                .expectComplete().verify(Duration.ofSeconds(10));
        }
}
