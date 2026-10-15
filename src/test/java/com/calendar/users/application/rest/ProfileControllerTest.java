package com.calendar.users.application.rest;

import com.calendar.users.application.dtos.ProvisionUserRequest;
import com.calendar.users.application.dtos.UpdateProfileRequest;
import com.calendar.users.application.mappers.ProvisionUserRequestMapperImpl;
import com.calendar.users.application.mappers.UpdateProfileRequestMapperImpl;
import com.calendar.users.domain.models.ProfileUpdate;
import com.calendar.users.exception.BusinessErrorCode;
import com.calendar.users.exception.BusinessException;
import com.calendar.users.exception.TechnicalErrorCode;
import com.calendar.users.exception.TechnicalException;
import com.calendar.users.exception.GlobalErrorHandler;
import com.calendar.users.domain.models.BusinessUser;
import com.calendar.users.domain.models.IdentityUser;
import com.calendar.users.domain.services.AccountDeletionService;
import com.calendar.users.domain.services.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.MethodParameter;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.BindingContext;
import org.springframework.web.reactive.result.method.HandlerMethodArgumentResolver;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProfileControllerTest {

    @Mock
    private UserService userService;

    @Mock
    private AccountDeletionService accountDeletionService;

    private WebTestClient webTestClient;
    private Jwt mockJwt;

    @BeforeEach
    void setUp() {
        mockJwt = mock(Jwt.class);

        webTestClient = WebTestClient.bindToController(new ProfileController(userService, accountDeletionService,
                        new ProvisionUserRequestMapperImpl(), new UpdateProfileRequestMapperImpl()))
                .controllerAdvice(new GlobalErrorHandler())
                .argumentResolvers(configurer -> configurer.addCustomResolver(new HandlerMethodArgumentResolver() {
                    @Override
                    public boolean supportsParameter(MethodParameter parameter) {
                        return parameter.getParameterType().equals(Jwt.class);
                    }

                    @Override
                    public Mono<Object> resolveArgument(MethodParameter parameter, BindingContext bindingContext,
                            ServerWebExchange exchange) {
                        return Mono.just(mockJwt);
                    }
                }))
                .build();
    }

    @Test
    void findBusinessIdByKeycloakId_ShouldReturnTheBusinessId() {
        // Given
        String kcId = "kc-123";
        UUID internalId = UUID.randomUUID();
        when(userService.findBusinessIdByKeycloakId(kcId)).thenReturn(Mono.just(internalId));

        // When & Then
        webTestClient.get()
                .uri("/profile/resolve/{keycloakId}", kcId)
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .isEqualTo(internalId.toString());
    }

    @Test
    void readProfile_ShouldReturnUser() {
        // Given
        UUID userId = UUID.randomUUID();
        when(mockJwt.getClaimAsString("businessId")).thenReturn(userId.toString());

        BusinessUser user = new BusinessUser(userId, "user", 1, "F", "L", "url", LocalDateTime.now());
        when(userService.readProfile(userId)).thenReturn(Mono.just(user));

        // When & Then
        webTestClient.get()
                .uri("/profile")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.userName").isEqualTo("user");
    }


    @Test
    void provisionUser_ShouldReturnTheBusinessId() {
        UUID businessId = UUID.randomUUID();
        when(userService.provisionUser("kc-1", new IdentityUser("léa", "Léa", "Martin")))
                .thenReturn(Mono.just(businessId));

        webTestClient.post()
                .uri("/profile/provision")
                .bodyValue(new ProvisionUserRequest("kc-1", "léa", "Léa", "Martin"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .isEqualTo(businessId.toString());
    }

    @Test
    void updateProfile_ShouldUpdateTheCallersOwnProfile() {
        UUID userId = UUID.randomUUID();
        when(mockJwt.getClaimAsString("businessId")).thenReturn(userId.toString());
        BusinessUser updated = new BusinessUser(userId, "camille_d", 4321, "Camille", "Durand", null, LocalDateTime.now());
        when(userService.updateProfile(userId, new ProfileUpdate("camille_d", "Camille", "Durand")))
                .thenReturn(Mono.just(updated));

        webTestClient.put()
                .uri("/profile")
                .bodyValue(new UpdateProfileRequest("camille_d", "Camille", "Durand"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.userName").isEqualTo("camille_d")
                .jsonPath("$.hashtag").isEqualTo(4321);
    }

    @Test
    void updateProfile_ShouldAnswer409WithItsCode_WhenTheUsernameIsTaken() {
        UUID userId = UUID.randomUUID();
        when(mockJwt.getClaimAsString("businessId")).thenReturn(userId.toString());
        when(userService.updateProfile(any(), any()))
                .thenReturn(Mono.error(new BusinessException(BusinessErrorCode.USERNAME_TAKEN)));

        webTestClient.put()
                .uri("/profile")
                .bodyValue(new UpdateProfileRequest("lea", "Camille", "Durand"))
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody()
                .jsonPath("$.code").isEqualTo("USR-BUS-004");
    }

    @Test
    void updateProfile_ShouldRejectAUsernameWithForbiddenCharacters() {
        webTestClient.put()
                .uri("/profile")
                .bodyValue(new UpdateProfileRequest("camille durand!", "Camille", "Durand"))
                .exchange()
                .expectStatus().isBadRequest();

        verify(userService, never()).updateProfile(any(), any());
    }

    @Test
    void provisionUser_ShouldRejectARequestWithoutUsername() {
        webTestClient.post()
                .uri("/profile/provision")
                .bodyValue(new ProvisionUserRequest("kc-1", " ", "Léa", "Martin"))
                .exchange()
                .expectStatus().isBadRequest();
    }

    // --- updatePicture ----------------------------------------------------

    private static MultipartBodyBuilder pictureOf(int size) {
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("file", new ByteArrayResource(new byte[size]) {
            @Override
            public String getFilename() {
                return "avatar.jpg";
            }
        }).contentType(MediaType.IMAGE_JPEG);
        return body;
    }

    @Test
    void updatePicture_ShouldStoreTheCallersPictureAndAnswerItsUrl() {
        UUID userId = UUID.randomUUID();
        when(mockJwt.getClaimAsString("businessId")).thenReturn(userId.toString());
        when(userService.updatePicture(eq(userId), any())).thenReturn(Mono.just("https://media/signed"));

        webTestClient.post()
                .uri("/profile/picture")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(pictureOf(1024).build()))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.profilePicUrl").isEqualTo("https://media/signed");
    }

    @Test
    void updatePicture_ShouldAnswer413_AboveTheLimit() {
        when(mockJwt.getClaimAsString("businessId")).thenReturn(UUID.randomUUID().toString());

        webTestClient.post()
                .uri("/profile/picture")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(pictureOf(ProfileController.MAX_PICTURE_BYTES + 1).build()))
                .exchange()
                .expectStatus().isEqualTo(413)
                .expectBody()
                .jsonPath("$.code").isEqualTo("USR-BUS-007");

        verify(userService, never()).updatePicture(any(), any());
    }

    @Test
    void updatePicture_ShouldAnswer400_ForAFileThatIsNotAnImage() {
        when(mockJwt.getClaimAsString("businessId")).thenReturn(UUID.randomUUID().toString());
        when(userService.updatePicture(any(), any()))
                .thenReturn(Mono.error(new BusinessException(BusinessErrorCode.PICTURE_INVALID)));

        webTestClient.post()
                .uri("/profile/picture")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(pictureOf(16).build()))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.code").isEqualTo("USR-BUS-006");
    }

    @Test
    void updatePicture_ShouldAnswerWithoutAUrl_WhenTheSavedPictureCouldNotBeSigned() {
        when(mockJwt.getClaimAsString("businessId")).thenReturn(UUID.randomUUID().toString());
        when(userService.updatePicture(any(), any())).thenReturn(Mono.empty());

        webTestClient.post()
                .uri("/profile/picture")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(pictureOf(16).build()))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.profilePicUrl").isEmpty();
    }

    @Test
    void updatePicture_ShouldAnswer502_WhenTheMediaServiceIsDown() {
        when(mockJwt.getClaimAsString("businessId")).thenReturn(UUID.randomUUID().toString());
        when(userService.updatePicture(any(), any()))
                .thenReturn(Mono.error(new TechnicalException(TechnicalErrorCode.MEDIA_ERROR)));

        webTestClient.post()
                .uri("/profile/picture")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(pictureOf(16).build()))
                .exchange()
                .expectStatus().isEqualTo(502)
                .expectBody()
                .jsonPath("$.code").isEqualTo("USR-TEC-004");
    }

    // --- account deletion -------------------------------------------------

    private static final Instant SIGNED_IN = Instant.parse("2026-10-15T14:00:00Z");

    private UUID givenTheCaller() {
        UUID userId = UUID.randomUUID();
        when(mockJwt.getClaimAsString("businessId")).thenReturn(userId.toString());
        return userId;
    }

    @Test
    void deleteAccount_shouldAnswer202WithAnEmptyBody_whenTheDeletionIsAccepted() {
        UUID userId = givenTheCaller();
        when(mockJwt.getClaimAsInstant("auth_time")).thenReturn(SIGNED_IN);
        when(accountDeletionService.requestDeletion(userId, SIGNED_IN)).thenReturn(Mono.empty());

        webTestClient.delete()
                .uri("/profile")
                .exchange()
                .expectStatus().isAccepted()
                .expectBody().isEmpty();
    }

    @Test
    void deleteAccount_shouldPassTheBusinessIdAndAuthTimeFromTheToken_whenBothArePresent() {
        UUID userId = givenTheCaller();
        when(mockJwt.getClaimAsInstant("auth_time")).thenReturn(SIGNED_IN);
        when(accountDeletionService.requestDeletion(any(), any())).thenReturn(Mono.empty());

        webTestClient.delete().uri("/profile").exchange().expectStatus().isAccepted();

        verify(accountDeletionService).requestDeletion(userId, SIGNED_IN);
    }

    @Test
    void deleteAccount_shouldPassANullAuthTime_whenTheClaimIsMissing() {
        UUID userId = givenTheCaller();
        when(mockJwt.getClaimAsInstant("auth_time")).thenReturn(null);
        when(accountDeletionService.requestDeletion(eq(userId), isNull()))
                .thenReturn(Mono.error(new BusinessException(BusinessErrorCode.REAUTHENTICATION_REQUIRED)));

        webTestClient.delete().uri("/profile").exchange().expectStatus().isForbidden();

        verify(accountDeletionService).requestDeletion(userId, null);
    }

    @Test
    void deleteAccount_shouldPassANullAuthTime_whenTheClaimIsNotAnInstant() {
        // Spring Security throws on an unconvertible claim: read as absent, the check fails closed.
        UUID userId = givenTheCaller();
        when(mockJwt.getClaimAsInstant("auth_time")).thenThrow(new IllegalArgumentException("not an instant"));
        when(accountDeletionService.requestDeletion(eq(userId), isNull()))
                .thenReturn(Mono.error(new BusinessException(BusinessErrorCode.REAUTHENTICATION_REQUIRED)));

        webTestClient.delete().uri("/profile").exchange().expectStatus().isForbidden();

        verify(accountDeletionService).requestDeletion(userId, null);
    }

    @Test
    void deleteAccount_shouldAnswer403WithUsrBus010_whenReauthenticationIsRequired() {
        givenTheCaller();
        when(accountDeletionService.requestDeletion(any(), any()))
                .thenReturn(Mono.error(new BusinessException(BusinessErrorCode.REAUTHENTICATION_REQUIRED)));

        webTestClient.delete()
                .uri("/profile")
                .exchange()
                .expectStatus().isForbidden()
                .expectBody().jsonPath("$.code").isEqualTo("USR-BUS-010");
    }

    @Test
    void deleteAccount_shouldAnswer403WithUsrBus011_whenTheAccountIsProtected() {
        givenTheCaller();
        when(accountDeletionService.requestDeletion(any(), any()))
                .thenReturn(Mono.error(new BusinessException(BusinessErrorCode.ACCOUNT_PROTECTED)));

        webTestClient.delete()
                .uri("/profile")
                .exchange()
                .expectStatus().isForbidden()
                .expectBody().jsonPath("$.code").isEqualTo("USR-BUS-011");
    }

    @Test
    void deleteAccount_shouldAnswer403WithUsrBus014_whenDeletionIsDisabled() {
        givenTheCaller();
        when(accountDeletionService.requestDeletion(any(), any()))
                .thenReturn(Mono.error(new BusinessException(BusinessErrorCode.ACCOUNT_DELETION_DISABLED)));

        webTestClient.delete()
                .uri("/profile")
                .exchange()
                .expectStatus().isForbidden()
                .expectBody().jsonPath("$.code").isEqualTo("USR-BUS-014");
    }

    @Test
    void deleteAccount_shouldAnswer404_whenTheUserIsUnknown() {
        givenTheCaller();
        when(accountDeletionService.requestDeletion(any(), any()))
                .thenReturn(Mono.error(new BusinessException(BusinessErrorCode.USER_NOT_FOUND)));

        webTestClient.delete()
                .uri("/profile")
                .exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.code").isEqualTo("USR-BUS-001");
    }

    @Test
    void deleteAccount_shouldAnswer502_whenTheDatabaseIsDown() {
        givenTheCaller();
        when(accountDeletionService.requestDeletion(any(), any()))
                .thenReturn(Mono.error(new TechnicalException(TechnicalErrorCode.DATABASE_ERROR)));

        webTestClient.delete()
                .uri("/profile")
                .exchange()
                .expectStatus().isEqualTo(502)
                .expectBody().jsonPath("$.code").isEqualTo("USR-TEC-002");
    }

    @Test
    void deletionEligibility_shouldAnswerAllowedTrue_whenTheAccountMayBeDeleted() {
        UUID userId = givenTheCaller();
        when(accountDeletionService.isDeletionAllowed(userId)).thenReturn(Mono.just(true));

        webTestClient.get()
                .uri("/profile/deletion")
                .exchange()
                .expectStatus().isOk()
                .expectBody().json("{\"allowed\":true}", true);
    }

    @Test
    void deletionEligibility_shouldAnswerAllowedFalse_whenDeletionIsNotAllowed() {
        UUID userId = givenTheCaller();
        when(accountDeletionService.isDeletionAllowed(userId)).thenReturn(Mono.just(false));

        webTestClient.get()
                .uri("/profile/deletion")
                .exchange()
                .expectStatus().isOk()
                .expectBody().json("{\"allowed\":false}", true);
    }

    @Test
    void deletionEligibility_shouldAnswer403WithUsrBus014_whenDeletionIsDisabled() {
        UUID userId = givenTheCaller();
        when(accountDeletionService.isDeletionAllowed(userId))
                .thenReturn(Mono.error(new BusinessException(BusinessErrorCode.ACCOUNT_DELETION_DISABLED)));

        webTestClient.get()
                .uri("/profile/deletion")
                .exchange()
                .expectStatus().isForbidden()
                .expectBody().jsonPath("$.code").isEqualTo("USR-BUS-014");
    }

    @Test
    void deletionEligibility_shouldAnswer410_whenTheAccountIsDeleted() {
        UUID userId = givenTheCaller();
        when(accountDeletionService.isDeletionAllowed(userId))
                .thenReturn(Mono.error(new BusinessException(BusinessErrorCode.USER_DELETED)));

        webTestClient.get()
                .uri("/profile/deletion")
                .exchange()
                .expectStatus().isEqualTo(410)
                .expectBody().jsonPath("$.code").isEqualTo("USR-BUS-012");
    }

    @Test
    void readProfile_shouldAnswer410WithUsrBus012_whenTheCallerIsATombstone() {
        UUID userId = givenTheCaller();
        when(userService.readProfile(userId))
                .thenReturn(Mono.error(new BusinessException(BusinessErrorCode.USER_DELETED)));

        webTestClient.get()
                .uri("/profile")
                .exchange()
                .expectStatus().isEqualTo(410)
                .expectBody().jsonPath("$.code").isEqualTo("USR-BUS-012");
    }

    @Test
    void findBusinessIdByKeycloakId_shouldAnswer404_whenTheAccountWasDeleted() {
        // Never 410: the Keycloak mapper would read it as an outage and mint a token from its cache.
        when(userService.findBusinessIdByKeycloakId("kc-deleted"))
                .thenReturn(Mono.error(new BusinessException(BusinessErrorCode.USER_NOT_FOUND)));

        webTestClient.get()
                .uri("/profile/resolve/{keycloakId}", "kc-deleted")
                .exchange()
                .expectStatus().isNotFound();
    }
}
