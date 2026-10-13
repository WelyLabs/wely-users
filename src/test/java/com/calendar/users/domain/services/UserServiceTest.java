package com.calendar.users.domain.services;

import com.calendar.users.domain.models.BusinessUser;
import com.calendar.users.domain.ports.IdentityProvider;
import com.calendar.users.domain.ports.PictureNormalizer;
import com.calendar.users.domain.ports.PictureStorage;
import com.calendar.users.domain.models.UserPicture;
import java.util.Map;
import java.util.Set;
import org.mockito.ArgumentCaptor;
import com.calendar.users.domain.models.ProfileUpdate;
import org.mockito.InOrder;
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

        @Mock
        private PictureStorage pictureStorage;

        @Mock
        private PictureNormalizer pictureNormalizer;


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

        // --- updateProfile ---------------------------------------------------

        private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
        private static final LocalDateTime JOINED = LocalDateTime.of(2026, 9, 1, 10, 0);
        private static final BusinessUser CAMILLE =
                        new BusinessUser(USER_ID, "camille", 4321, "Camille", "Durand", "pic.jpg", JOINED);
        private static final IdentityUser CAMILLE_BEFORE = new IdentityUser("camille", "Camille", "Durand");

        private void givenCamille() {
                when(userRepository.getBusinessUserByUserId(USER_ID)).thenReturn(Mono.just(CAMILLE));
                when(userRepository.findKeycloakIdByUserId(USER_ID)).thenReturn(Mono.just("kc-camille"));
        }

        @Test
        void updateProfile_ShouldChangeTheIdentityProviderThenTheDatabase_KeepingHashtagAndPicture() {
                givenCamille();
                BusinessUser expected = new BusinessUser(USER_ID, "camille_d", 4321, "Cam", "Dupont", "pic.jpg", JOINED);
                when(identityProvider.updateProfile(any(), any())).thenReturn(Mono.empty());
                when(userRepository.updateProfile(expected)).thenReturn(Mono.just(expected));

                StepVerifier.create(userService.updateProfile(USER_ID, new ProfileUpdate("camille_d", "Cam", "Dupont")))
                                .expectNext(expected)
                                .verifyComplete();

                InOrder order = inOrder(identityProvider, userRepository);
                order.verify(identityProvider).updateProfile("kc-camille", new IdentityUser("camille_d", "Cam", "Dupont"));
                order.verify(userRepository).updateProfile(expected);
        }

        @Test
        void updateProfile_ShouldLowercaseTheUsername_AsTheIdentityProviderStoresIt() {
                givenCamille();
                when(identityProvider.updateProfile(any(), any())).thenReturn(Mono.empty());
                when(userRepository.updateProfile(any())).thenAnswer(call -> Mono.just(call.getArgument(0)));

                StepVerifier.create(userService.updateProfile(USER_ID, new ProfileUpdate("Camille_D", "Cam", "Dupont")))
                                .expectNextMatches(user -> user.userName().equals("camille_d"))
                                .verifyComplete();

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
                                .verify();

                verify(userRepository, never()).updateProfile(any());
                verify(identityProvider, times(1)).updateProfile(any(), any());
        }

        @Test
        void updateProfile_ShouldGiveTheIdentityProviderItsPreviousValuesBack_WhenTheDatabaseRefuses() {
                givenCamille();
                when(identityProvider.updateProfile(any(), any())).thenReturn(Mono.empty());
                when(userRepository.updateProfile(any()))
                                .thenReturn(Mono.error(new BusinessException(BusinessErrorCode.USERNAME_TAKEN)));

                StepVerifier.create(userService.updateProfile(USER_ID, new ProfileUpdate("lea", "Cam", "Dupont")))
                                .expectErrorMatches(error -> error instanceof BusinessException business
                                                && business.getErrorCode() == BusinessErrorCode.USERNAME_TAKEN)
                                .verify();

                InOrder order = inOrder(identityProvider, userRepository);
                order.verify(identityProvider).updateProfile("kc-camille", new IdentityUser("lea", "Cam", "Dupont"));
                order.verify(userRepository).updateProfile(any());
                order.verify(identityProvider).updateProfile("kc-camille", CAMILLE_BEFORE);
        }

        @Test
        void updateProfile_ShouldKeepTheOriginalError_WhenTheUndoFailsToo() {
                givenCamille();
                RuntimeException databaseDown = new IllegalStateException("database down");
                RuntimeException keycloakDown = new IllegalStateException("keycloak down");
                when(identityProvider.updateProfile(eq("kc-camille"), any()))
                                .thenReturn(Mono.empty())
                                .thenReturn(Mono.error(keycloakDown));
                when(userRepository.updateProfile(any())).thenReturn(Mono.error(databaseDown));

                StepVerifier.create(userService.updateProfile(USER_ID, new ProfileUpdate("camille_d", "Cam", "Dupont")))
                                .expectErrorMatches(error -> error == databaseDown
                                                && error.getSuppressed().length == 1
                                                && error.getSuppressed()[0] == keycloakDown)
                                .verify();
        }

        @Test
        void updateProfile_ShouldError_WhenTheUserDoesNotExist() {
                when(userRepository.getBusinessUserByUserId(USER_ID)).thenReturn(Mono.empty());
                when(userRepository.findKeycloakIdByUserId(USER_ID)).thenReturn(Mono.empty());

                StepVerifier.create(userService.updateProfile(USER_ID, new ProfileUpdate("camille", "Cam", "Dupont")))
                                .expectErrorMatches(error -> error instanceof BusinessException business
                                                && business.getErrorCode() == BusinessErrorCode.USER_NOT_FOUND)
                                .verify();

                verify(identityProvider, never()).updateProfile(any(), any());
                verify(userRepository, never()).updateProfile(any());
        }

        // --- pictures --------------------------------------------------------

        private static final byte[] UPLOAD = {1, 2, 3};
        private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8};

        private BusinessUser camilleWithPicture(String reference) {
                return new BusinessUser(USER_ID, "camille", 4321, "Camille", "Durand", reference, JOINED);
        }

        private void givenANormalizedPicture() {
                when(pictureNormalizer.normalize(UPLOAD)).thenReturn(Mono.just(JPEG));
                when(pictureNormalizer.contentType()).thenReturn("image/jpeg");
                when(pictureStorage.put(anyString(), eq(JPEG), eq("image/jpeg"))).thenReturn(Mono.empty());
        }

        @Test
        void updatePicture_ShouldStoreUnderAFreshKey_PointTheUserAtIt_ThenRemoveThePreviousUpload() {
                when(userRepository.getBusinessUserByUserId(USER_ID))
                                .thenReturn(Mono.just(camilleWithPicture("avatars/" + USER_ID + "/old.jpg")));
                givenANormalizedPicture();
                when(userRepository.updatePicture(eq(USER_ID), anyString())).thenReturn(Mono.just(CAMILLE));
                when(pictureStorage.delete("avatars/" + USER_ID + "/old.jpg")).thenReturn(Mono.empty());
                when(pictureStorage.signedUrl(anyString(), eq(UserService.PICTURE_URL_TTL))).thenReturn("https://media/signed");

                StepVerifier.create(userService.updatePicture(USER_ID, UPLOAD))
                                .expectNext("https://media/signed")
                                .verifyComplete();

                ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
                InOrder order = inOrder(pictureStorage, userRepository);
                order.verify(pictureStorage).put(key.capture(), eq(JPEG), eq("image/jpeg"));
                order.verify(userRepository).updatePicture(USER_ID, key.getValue());
                order.verify(pictureStorage).delete("avatars/" + USER_ID + "/old.jpg");
                org.assertj.core.api.Assertions.assertThat(key.getValue())
                                .matches("avatars/" + USER_ID + "/[0-9a-f-]{36}\\.jpg");
        }

        @Test
        void updatePicture_ShouldLeaveAPathTheWebAppServes_WhenReplacingIt() {
                when(userRepository.getBusinessUserByUserId(USER_ID))
                                .thenReturn(Mono.just(camilleWithPicture("/demo/avatars/camille.svg")));
                givenANormalizedPicture();
                when(userRepository.updatePicture(eq(USER_ID), anyString())).thenReturn(Mono.just(CAMILLE));
                when(pictureStorage.signedUrl(anyString(), any())).thenReturn("https://media/signed");

                StepVerifier.create(userService.updatePicture(USER_ID, UPLOAD)).expectNextCount(1).verifyComplete();

                verify(pictureStorage, never()).delete(anyString());
        }

        @Test
        void updatePicture_ShouldRemoveTheNewFile_WhenTheDatabaseRefuses() {
                when(userRepository.getBusinessUserByUserId(USER_ID)).thenReturn(Mono.just(camilleWithPicture(null)));
                givenANormalizedPicture();
                when(userRepository.updatePicture(eq(USER_ID), anyString()))
                                .thenReturn(Mono.error(new IllegalStateException("database down")));
                when(pictureStorage.delete(anyString())).thenReturn(Mono.empty());

                StepVerifier.create(userService.updatePicture(USER_ID, UPLOAD))
                                .expectError(IllegalStateException.class)
                                .verify();

                ArgumentCaptor<String> stored = ArgumentCaptor.forClass(String.class);
                verify(pictureStorage).put(stored.capture(), eq(JPEG), eq("image/jpeg"));
                verify(pictureStorage).delete(stored.getValue());
                verify(pictureStorage, never()).signedUrl(anyString(), any());
        }

        @Test
        void updatePicture_ShouldStoreNothing_WhenTheUploadIsNotAnImage() {
                when(userRepository.getBusinessUserByUserId(USER_ID)).thenReturn(Mono.just(camilleWithPicture(null)));
                when(pictureNormalizer.normalize(UPLOAD))
                                .thenReturn(Mono.error(new BusinessException(BusinessErrorCode.PICTURE_INVALID)));

                StepVerifier.create(userService.updatePicture(USER_ID, UPLOAD))
                                .expectErrorMatches(error -> error instanceof BusinessException business
                                                && business.getErrorCode() == BusinessErrorCode.PICTURE_INVALID)
                                .verify();

                verify(pictureStorage, never()).put(anyString(), any(), anyString());
                verify(userRepository, never()).updatePicture(any(), anyString());
        }

        @Test
        void updatePicture_ShouldStillSucceed_WhenThePreviousFileCannotBeRemoved() {
                when(userRepository.getBusinessUserByUserId(USER_ID))
                                .thenReturn(Mono.just(camilleWithPicture("avatars/" + USER_ID + "/old.jpg")));
                givenANormalizedPicture();
                when(userRepository.updatePicture(eq(USER_ID), anyString())).thenReturn(Mono.just(CAMILLE));
                when(pictureStorage.delete("avatars/" + USER_ID + "/old.jpg"))
                                .thenReturn(Mono.error(new IllegalStateException("storage down")));
                when(pictureStorage.signedUrl(anyString(), any())).thenReturn("https://media/signed");

                StepVerifier.create(userService.updatePicture(USER_ID, UPLOAD))
                                .expectNext("https://media/signed")
                                .verifyComplete();
        }

        @Test
        void updatePicture_ShouldError_WhenTheUserDoesNotExist() {
                when(userRepository.getBusinessUserByUserId(USER_ID)).thenReturn(Mono.empty());

                StepVerifier.create(userService.updatePicture(USER_ID, UPLOAD))
                                .expectErrorMatches(error -> error instanceof BusinessException business
                                                && business.getErrorCode() == BusinessErrorCode.USER_NOT_FOUND)
                                .verify();

                verify(pictureNormalizer, never()).normalize(any());
        }

        @Test
        void pictureUrls_ShouldSignUploadsAndPassPathsThrough() {
                UUID lea = UUID.fromString("22222222-2222-2222-2222-222222222222");
                when(userRepository.findPictures(Set.of(USER_ID, lea))).thenReturn(Flux.just(
                                new UserPicture(USER_ID, "avatars/" + USER_ID + "/a.jpg"),
                                new UserPicture(lea, "/demo/avatars/lea.svg")));
                when(pictureStorage.signedUrl("avatars/" + USER_ID + "/a.jpg", UserService.PICTURE_URL_TTL))
                                .thenReturn("https://media/signed");

                StepVerifier.create(userService.pictureUrls(Set.of(USER_ID, lea)))
                                .expectNext(Map.of(USER_ID, "https://media/signed", lea, "/demo/avatars/lea.svg"))
                                .verifyComplete();
        }
}
