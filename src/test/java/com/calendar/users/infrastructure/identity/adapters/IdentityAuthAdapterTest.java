package com.calendar.users.infrastructure.identity.adapters;

import com.calendar.users.domain.models.IdentityUser;
import com.calendar.users.exception.BusinessErrorCode;
import com.calendar.users.exception.BusinessException;
import com.calendar.users.infrastructure.identity.api.KeycloakAdminApi;
import com.calendar.users.infrastructure.identity.mappers.IdentityUserMapper;
import com.calendar.users.infrastructure.identity.models.KeycloakUserResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Map;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * This adapter is where the vendor stops.
 *
 * <p>Failure translation is no longer here: it moved to
 * {@code InfrastructureErrorAspect}, under KEYCLOAK_ERROR rather than DATABASE_ERROR — an
 * unreachable Keycloak is a different incident. See
 * {@code InfrastructureErrorAspectWiringTest}.
 */
@ExtendWith(MockitoExtension.class)
class IdentityAuthAdapterTest {

    @Mock private KeycloakAdminApi keycloakAdminApi;
    @Mock private IdentityUserMapper identityUserMapper;

    private IdentityAuthAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new IdentityAuthAdapter(keycloakAdminApi, identityUserMapper);
    }

    @Test
    @DisplayName("the Keycloak payload is translated into the domain model")
    void getUser_shouldReturnTheDomainModel() {
        KeycloakUserResponse wirePayload =
                new KeycloakUserResponse("testuser", "First", "Last", "a@b.c", true);
        IdentityUser domainUser = new IdentityUser("testuser", "First", "Last");

        when(keycloakAdminApi.getUser("kc-1")).thenReturn(Mono.just(wirePayload));
        when(identityUserMapper.toIdentityUser(wirePayload)).thenReturn(domainUser);

        StepVerifier.create(adapter.getUser("kc-1"))
                .expectNext(domainUser)
                .verifyComplete();

        verify(identityUserMapper).toIdentityUser(wirePayload);
    }

    @Test
    @DisplayName("the Keycloak type does not cross the port boundary")
    void getUser_shouldNotLeakTheVendorTypeThroughThePort() throws Exception {
        // The port used to declare Mono<KeycloakUserResponse>: the domain would not
        // compile without the Keycloak client on the classpath, and swapping identity
        // providers would have meant editing the domain.
        var portMethod = com.calendar.users.domain.ports.IdentityProvider.class
                .getMethod("getUser", String.class);

        assertThat(portMethod.getGenericReturnType().getTypeName())
                .contains("IdentityUser")
                .doesNotContain("Keycloak");
    }

    @Test
    void getUser_shouldStayEmptyWhenTheProviderKnowsNoSuchSubject() {
        when(keycloakAdminApi.getUser("unknown")).thenReturn(Mono.empty());

        StepVerifier.create(adapter.getUser("unknown")).verifyComplete();
    }

    // --- updateProfile -------------------------------------------------------

    private static final IdentityUser RENAMED = new IdentityUser("camille_d", "Cam", "Dupont");

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("an update writes back the whole representation, changing only the three fields")
    void updateProfile_shouldKeepEveryOtherField() {
        Map<String, Object> current = Map.of("id", "kc-1", "username", "camille", "firstName", "Camille",
                "lastName", "Durand", "email", "camille@example.org", "attributes", Map.of("locale", "fr"));
        when(keycloakAdminApi.getUserRepresentation("kc-1")).thenReturn(Mono.just(current));
        when(keycloakAdminApi.updateUser(eq("kc-1"), any())).thenReturn(Mono.empty());

        StepVerifier.create(adapter.updateProfile("kc-1", RENAMED)).verifyComplete();

        ArgumentCaptor<Map<String, Object>> written = ArgumentCaptor.forClass(Map.class);
        verify(keycloakAdminApi).updateUser(eq("kc-1"), written.capture());
        assertThat(written.getValue())
                .containsEntry("username", "camille_d")
                .containsEntry("firstName", "Cam")
                .containsEntry("lastName", "Dupont")
                .containsEntry("email", "camille@example.org")
                .containsEntry("attributes", Map.of("locale", "fr"));
    }

    @Test
    @DisplayName("Keycloak's 409 is a taken username, which the user can fix")
    void updateProfile_shouldMapAConflictToUsernameTaken() {
        when(keycloakAdminApi.getUserRepresentation("kc-1")).thenReturn(Mono.just(Map.of("username", "camille")));
        when(keycloakAdminApi.updateUser(eq("kc-1"), any())).thenReturn(Mono.error(
                WebClientResponseException.create(HttpStatus.CONFLICT.value(), "Conflict", null, null, null)));

        StepVerifier.create(adapter.updateProfile("kc-1", RENAMED))
                .expectErrorMatches(error -> error instanceof BusinessException business
                        && business.getErrorCode() == BusinessErrorCode.USERNAME_TAKEN)
                .verify();
    }

    @Test
    @DisplayName("Keycloak's 400 is a profile its rules refuse")
    void updateProfile_shouldMapABadRequestToProfileInvalid() {
        when(keycloakAdminApi.getUserRepresentation("kc-1")).thenReturn(Mono.just(Map.of("username", "camille")));
        when(keycloakAdminApi.updateUser(eq("kc-1"), any())).thenReturn(Mono.error(
                WebClientResponseException.create(HttpStatus.BAD_REQUEST.value(), "Bad Request", null, null, null)));

        StepVerifier.create(adapter.updateProfile("kc-1", RENAMED))
                .expectErrorMatches(error -> error instanceof BusinessException business
                        && business.getErrorCode() == BusinessErrorCode.PROFILE_INVALID)
                .verify();
    }

    @Test
    @DisplayName("an outage is left to the aspect, which reports KEYCLOAK_ERROR")
    void updateProfile_shouldLeaveOtherFailuresUntranslated() {
        when(keycloakAdminApi.getUserRepresentation("kc-1")).thenReturn(Mono.error(
                WebClientResponseException.create(HttpStatus.SERVICE_UNAVAILABLE.value(), "Unavailable", null, null, null)));

        StepVerifier.create(adapter.updateProfile("kc-1", RENAMED))
                .expectError(WebClientResponseException.ServiceUnavailable.class)
                .verify();

        verify(keycloakAdminApi, never()).updateUser(any(), any());
    }

    // --- deleteSubject -------------------------------------------------------

    private static WebClientResponseException status(HttpStatus status) {
        return WebClientResponseException.create(status.value(), status.getReasonPhrase(), null, null, null);
    }

    @Test
    void deleteSubject_shouldLogOutThenDelete_whenTheSubjectExists() {
        when(keycloakAdminApi.logout("kc-1")).thenReturn(Mono.empty());
        when(keycloakAdminApi.deleteUser("kc-1")).thenReturn(Mono.empty());

        StepVerifier.create(adapter.deleteSubject("kc-1")).verifyComplete();

        InOrder order = inOrder(keycloakAdminApi);
        order.verify(keycloakAdminApi).logout("kc-1");
        order.verify(keycloakAdminApi).deleteUser("kc-1");
    }

    @Test
    void deleteSubject_shouldStillDelete_whenLogoutAnswers404() {
        when(keycloakAdminApi.logout("kc-1")).thenReturn(Mono.error(status(HttpStatus.NOT_FOUND)));
        when(keycloakAdminApi.deleteUser("kc-1")).thenReturn(Mono.empty());

        StepVerifier.create(adapter.deleteSubject("kc-1")).verifyComplete();

        verify(keycloakAdminApi).deleteUser("kc-1");
    }

    @Test
    @DisplayName("a subject Keycloak no longer knows is already deleted: the step is idempotent")
    void deleteSubject_shouldComplete_whenDeleteAnswers404() {
        when(keycloakAdminApi.logout("kc-1")).thenReturn(Mono.empty());
        when(keycloakAdminApi.deleteUser("kc-1")).thenReturn(Mono.error(status(HttpStatus.NOT_FOUND)));

        StepVerifier.create(adapter.deleteSubject("kc-1")).verifyComplete();
    }

    @Test
    void deleteSubject_shouldNotDelete_whenLogoutFailsWithAServerError() {
        when(keycloakAdminApi.logout("kc-1")).thenReturn(Mono.error(status(HttpStatus.SERVICE_UNAVAILABLE)));

        StepVerifier.create(adapter.deleteSubject("kc-1"))
                .expectError(WebClientResponseException.ServiceUnavailable.class)
                .verify();

        verify(keycloakAdminApi, never()).deleteUser(any());
    }

    @Test
    void deleteSubject_shouldFail_whenDeleteFailsWithAServerError() {
        when(keycloakAdminApi.logout("kc-1")).thenReturn(Mono.empty());
        when(keycloakAdminApi.deleteUser("kc-1")).thenReturn(Mono.error(status(HttpStatus.INTERNAL_SERVER_ERROR)));

        StepVerifier.create(adapter.deleteSubject("kc-1"))
                .expectError(WebClientResponseException.InternalServerError.class)
                .verify();
    }

    @Test
    void deleteSubject_shouldFailWithATimeout_whenKeycloakDoesNotAnswer() {
        when(keycloakAdminApi.logout("kc-1")).thenReturn(Mono.never());

        StepVerifier.withVirtualTime(() -> adapter.deleteSubject("kc-1"))
                .expectSubscription()
                .thenAwait(IdentityAuthAdapter.DELETE_TIMEOUT)
                .expectError(TimeoutException.class)
                .verify();

        verify(keycloakAdminApi, never()).deleteUser(any());
    }
}
