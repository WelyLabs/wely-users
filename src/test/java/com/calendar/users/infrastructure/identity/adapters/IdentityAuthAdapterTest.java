package com.calendar.users.infrastructure.identity.adapters;

import com.calendar.users.domain.models.IdentityUser;
import com.calendar.users.exception.BusinessErrorCode;
import com.calendar.users.exception.BusinessException;
import com.calendar.users.infrastructure.identity.api.Auth0ManagementApi;
import com.calendar.users.infrastructure.identity.mappers.IdentityUserMapper;
import com.calendar.users.infrastructure.identity.models.Auth0UserResponse;
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

import java.nio.charset.StandardCharsets;
import java.time.Duration;
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
 * {@code InfrastructureErrorAspect}, under IDENTITY_PROVIDER_ERROR rather than DATABASE_ERROR — an
 * unreachable Auth0 is a different incident. See
 * {@code InfrastructureErrorAspectWiringTest}.
 */
@ExtendWith(MockitoExtension.class)
class IdentityAuthAdapterTest {

    @Mock private Auth0ManagementApi managementApi;
    @Mock private IdentityUserMapper identityUserMapper;

    private IdentityAuthAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new IdentityAuthAdapter(managementApi, identityUserMapper);
    }

    @Test
    @DisplayName("the Auth0 payload is translated into the domain model")
    void getUser_shouldReturnTheDomainModel() {
        Auth0UserResponse wirePayload =
                new Auth0UserResponse("testuser", "First", "Last", "a@b.c", true);
        IdentityUser domainUser = new IdentityUser("testuser", "First", "Last");

        when(managementApi.getUser("auth0|1")).thenReturn(Mono.just(wirePayload));
        when(identityUserMapper.toIdentityUser(wirePayload)).thenReturn(domainUser);

        StepVerifier.create(adapter.getUser("auth0|1"))
                .expectNext(domainUser)
                .expectComplete().verify(Duration.ofSeconds(10));

        verify(identityUserMapper).toIdentityUser(wirePayload);
    }

    @Test
    @DisplayName("the Auth0 type does not cross the port boundary")
    void getUser_shouldNotLeakTheVendorTypeThroughThePort() throws Exception {
        // The port used to declare Mono<Auth0UserResponse>: the domain would not
        // compile without the vendor client on the classpath, and swapping identity
        // providers would have meant editing the domain.
        var portMethod = com.calendar.users.domain.ports.IdentityProvider.class
                .getMethod("getUser", String.class);

        assertThat(portMethod.getGenericReturnType().getTypeName())
                .contains("IdentityUser")
                .doesNotContain("Auth0");
    }

    @Test
    void getUser_shouldStayEmptyWhenTheProviderKnowsNoSuchSubject() {
        when(managementApi.getUser("unknown")).thenReturn(Mono.empty());

        StepVerifier.create(adapter.getUser("unknown")).expectComplete().verify(Duration.ofSeconds(10));
    }

    // --- updateProfile -------------------------------------------------------

    private static final IdentityUser RENAMED = new IdentityUser("camille_d", "Cam", "Dupont");

    private static WebClientResponseException status(HttpStatus status) {
        return WebClientResponseException.create(status.value(), status.getReasonPhrase(), null, null, null);
    }

    private static WebClientResponseException badRequest(String body) {
        return WebClientResponseException.create(400, "Bad Request", null,
                body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> patched() {
        ArgumentCaptor<Map<String, Object>> written = ArgumentCaptor.forClass(Map.class);
        verify(managementApi).updateUser(eq("auth0|1"), written.capture());
        return written.getValue();
    }

    @Test
    @DisplayName("an update patches the three fields under Auth0's names, and nothing else")
    void updateProfile_shouldPatchOnlyTheThreeFields() {
        when(managementApi.updateUser(eq("auth0|1"), any())).thenReturn(Mono.empty());

        StepVerifier.create(adapter.updateProfile("auth0|1", RENAMED)).expectComplete().verify(Duration.ofSeconds(10));

        assertThat(patched()).containsExactlyInAnyOrderEntriesOf(
                Map.of("username", "camille_d", "given_name", "Cam", "family_name", "Dupont"));
    }

    @Test
    @DisplayName("a null name is left out: Auth0 types names as strings")
    void updateProfile_shouldLeaveOutNullNames() {
        when(managementApi.updateUser(eq("auth0|1"), any())).thenReturn(Mono.empty());

        StepVerifier.create(adapter.updateProfile("auth0|1", new IdentityUser("camille", null, null)))
                .expectComplete().verify(Duration.ofSeconds(10));

        assertThat(patched()).containsExactlyEntriesOf(Map.of("username", "camille"));
    }

    @Test
    void updateProfile_shouldMapAConflictToUsernameTaken() {
        when(managementApi.updateUser(eq("auth0|1"), any())).thenReturn(Mono.error(status(HttpStatus.CONFLICT)));

        StepVerifier.create(adapter.updateProfile("auth0|1", RENAMED))
                .expectErrorMatches(error -> error instanceof BusinessException business
                        && business.getErrorCode() == BusinessErrorCode.USERNAME_TAKEN)
                .verify(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("Auth0's 400 naming an existing username is a taken username")
    void updateProfile_shouldMapAnExistingUsernameBadRequestToUsernameTaken() {
        when(managementApi.updateUser(eq("auth0|1"), any())).thenReturn(Mono.error(badRequest(
                "{\"statusCode\":400,\"message\":\"The specified new username already exists\"}")));

        StepVerifier.create(adapter.updateProfile("auth0|1", RENAMED))
                .expectErrorMatches(error -> error instanceof BusinessException business
                        && business.getErrorCode() == BusinessErrorCode.USERNAME_TAKEN)
                .verify(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("any other 400 is a profile Auth0's rules refuse")
    void updateProfile_shouldMapABadRequestToProfileInvalid() {
        when(managementApi.updateUser(eq("auth0|1"), any())).thenReturn(Mono.error(badRequest(
                "{\"statusCode\":400,\"message\":\"Payload validation error\"}")));

        StepVerifier.create(adapter.updateProfile("auth0|1", RENAMED))
                .expectErrorMatches(error -> error instanceof BusinessException business
                        && business.getErrorCode() == BusinessErrorCode.PROFILE_INVALID)
                .verify(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("an outage or a 429 is left to the aspect, which reports IDENTITY_PROVIDER_ERROR")
    void updateProfile_shouldLeaveOtherFailuresUntranslated() {
        when(managementApi.updateUser(eq("auth0|1"), any())).thenReturn(Mono.error(status(HttpStatus.TOO_MANY_REQUESTS)));

        StepVerifier.create(adapter.updateProfile("auth0|1", RENAMED))
                .expectError(WebClientResponseException.TooManyRequests.class)
                .verify(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("a Google or GitHub subject is not patched: Auth0 refuses root attributes of social users")
    void updateProfile_shouldSkipAuth0_whenTheSubjectComesFromASocialConnection() {
        StepVerifier.create(adapter.updateProfile("google-oauth2|1", RENAMED)).expectComplete().verify(Duration.ofSeconds(10));
        StepVerifier.create(adapter.updateProfile("github|1", RENAMED)).expectComplete().verify(Duration.ofSeconds(10));

        verify(managementApi, never()).updateUser(any(), any());
    }

    // --- deleteSubject -------------------------------------------------------

    @Test
    @DisplayName("a social subject is deleted like a database one")
    void deleteSubject_shouldEndTheSessionsThenDelete_whenTheSubjectComesFromASocialConnection() {
        when(managementApi.deleteSessions("google-oauth2|1")).thenReturn(Mono.empty());
        when(managementApi.deleteUser("google-oauth2|1")).thenReturn(Mono.empty());

        StepVerifier.create(adapter.deleteSubject("google-oauth2|1")).expectComplete().verify(Duration.ofSeconds(10));

        InOrder order = inOrder(managementApi);
        order.verify(managementApi).deleteSessions("google-oauth2|1");
        order.verify(managementApi).deleteUser("google-oauth2|1");
    }

    @Test
    void deleteSubject_shouldEndTheSessionsThenDelete_whenTheSubjectExists() {
        when(managementApi.deleteSessions("auth0|1")).thenReturn(Mono.empty());
        when(managementApi.deleteUser("auth0|1")).thenReturn(Mono.empty());

        StepVerifier.create(adapter.deleteSubject("auth0|1")).expectComplete().verify(Duration.ofSeconds(10));

        InOrder order = inOrder(managementApi);
        order.verify(managementApi).deleteSessions("auth0|1");
        order.verify(managementApi).deleteUser("auth0|1");
    }

    @Test
    void deleteSubject_shouldStillDelete_whenEndingTheSessionsAnswers404() {
        when(managementApi.deleteSessions("auth0|1")).thenReturn(Mono.error(status(HttpStatus.NOT_FOUND)));
        when(managementApi.deleteUser("auth0|1")).thenReturn(Mono.empty());

        StepVerifier.create(adapter.deleteSubject("auth0|1")).expectComplete().verify(Duration.ofSeconds(10));

        verify(managementApi).deleteUser("auth0|1");
    }

    @Test
    @DisplayName("sessions the plan or the scope does not let us end do not block the deletion")
    void deleteSubject_shouldStillDelete_whenEndingTheSessionsAnswers403() {
        when(managementApi.deleteSessions("auth0|1")).thenReturn(Mono.error(status(HttpStatus.FORBIDDEN)));
        when(managementApi.deleteUser("auth0|1")).thenReturn(Mono.empty());

        StepVerifier.create(adapter.deleteSubject("auth0|1")).expectComplete().verify(Duration.ofSeconds(10));

        verify(managementApi).deleteUser("auth0|1");
    }

    @Test
    @DisplayName("a subject Auth0 no longer knows is already deleted: the step is idempotent")
    void deleteSubject_shouldComplete_whenDeleteAnswers404() {
        when(managementApi.deleteSessions("auth0|1")).thenReturn(Mono.empty());
        when(managementApi.deleteUser("auth0|1")).thenReturn(Mono.error(status(HttpStatus.NOT_FOUND)));

        StepVerifier.create(adapter.deleteSubject("auth0|1")).expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void deleteSubject_shouldNotDelete_whenEndingTheSessionsIsRateLimited() {
        when(managementApi.deleteSessions("auth0|1")).thenReturn(Mono.error(status(HttpStatus.TOO_MANY_REQUESTS)));

        StepVerifier.create(adapter.deleteSubject("auth0|1"))
                .expectError(WebClientResponseException.TooManyRequests.class)
                .verify(Duration.ofSeconds(10));

        verify(managementApi, never()).deleteUser(any());
    }

    @Test
    void deleteSubject_shouldFail_whenDeleteFailsWithAServerError() {
        when(managementApi.deleteSessions("auth0|1")).thenReturn(Mono.empty());
        when(managementApi.deleteUser("auth0|1")).thenReturn(Mono.error(status(HttpStatus.INTERNAL_SERVER_ERROR)));

        StepVerifier.create(adapter.deleteSubject("auth0|1"))
                .expectError(WebClientResponseException.InternalServerError.class)
                .verify(Duration.ofSeconds(10));
    }

    @Test
    void deleteSubject_shouldFailWithATimeout_whenAuth0DoesNotAnswer() {
        when(managementApi.deleteSessions("auth0|1")).thenReturn(Mono.never());

        StepVerifier.withVirtualTime(() -> adapter.deleteSubject("auth0|1"))
                .expectSubscription()
                .thenAwait(IdentityAuthAdapter.DELETE_TIMEOUT)
                .expectError(TimeoutException.class)
                .verify();

        verify(managementApi, never()).deleteUser(any());
    }
}
