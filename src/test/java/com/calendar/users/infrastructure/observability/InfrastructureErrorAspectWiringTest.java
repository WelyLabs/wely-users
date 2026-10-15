package com.calendar.users.infrastructure.observability;

import com.calendar.users.domain.ports.AccountDeletionRepository;
import com.calendar.users.domain.ports.IdentityProvider;
import com.calendar.users.domain.ports.UserRepository;
import com.calendar.users.exception.TechnicalErrorCode;
import com.calendar.users.exception.TechnicalException;
import com.calendar.users.infrastructure.identity.api.KeycloakAdminApi;
import com.calendar.users.infrastructure.persistence.repositories.AccountDeletionR2dbcRepository;
import com.calendar.users.infrastructure.persistence.repositories.UserR2dbcRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Proves the two pointcuts actually match, in the real application context.
 *
 * <p>{@code InfrastructureErrorAspectTest} checks the advice logic in isolation, which says
 * nothing about whether it is ever applied: a typo in a pointcut's package name compiles,
 * starts, and silently advises nothing. Only a wired context catches that.
 *
 * <p>The two cases here are deliberately the two different error codes — if both pointcuts
 * resolved to the same one, or one failed to match, this test fails.
 */
@SpringBootTest
@ActiveProfiles("test")
class InfrastructureErrorAspectWiringTest {

    @MockitoBean private UserR2dbcRepository userR2dbcRepository;
    @MockitoBean private AccountDeletionR2dbcRepository accountDeletionR2dbcRepository;
    @MockitoBean private KeycloakAdminApi keycloakAdminApi;

    @Autowired private UserRepository userRepository;
    @Autowired private IdentityProvider identityProvider;
    @Autowired private AccountDeletionRepository accountDeletionRepository;

    @Test
    @DisplayName("both adapters are advised by the aspect")
    void adapters_shouldBeAdvised() {
        assertThat(AopUtils.isAopProxy(userRepository))
                .as("the persistence adapter should be an AOP proxy")
                .isTrue();
        assertThat(AopUtils.isAopProxy(identityProvider))
                .as("the identity adapter should be an AOP proxy")
                .isTrue();
    }

    @Test
    @DisplayName("a database failure surfaces as DATABASE_ERROR through the proxy")
    void persistenceAdapter_shouldTranslateToDatabaseError() {
        when(userR2dbcRepository.findIdByKeycloakId(anyString()))
                .thenReturn(Mono.error(new RuntimeException("connection reset by peer")));

        StepVerifier.create(userRepository.findIdByKeycloakId("kc-1"))
                .expectErrorMatches(error -> error instanceof TechnicalException
                        && ((TechnicalException) error).getErrorCode()
                                == TechnicalErrorCode.DATABASE_ERROR)
                .verify();
    }

    @Test
    @DisplayName("an unreachable Keycloak surfaces as KEYCLOAK_ERROR, not DATABASE_ERROR")
    void identityAdapter_shouldTranslateToKeycloakError() {
        when(keycloakAdminApi.getUser(anyString()))
                .thenReturn(Mono.error(new RuntimeException("503 Service Unavailable")));

        StepVerifier.create(identityProvider.getUser("kc-1"))
                .expectErrorMatches(error -> error instanceof TechnicalException
                        && ((TechnicalException) error).getErrorCode()
                                == TechnicalErrorCode.KEYCLOAK_ERROR)
                .verify();
    }

    @Test
    @DisplayName("the pointcut covers every public method of the adapter")
    void persistenceAdapter_shouldTranslateEveryMethod() {
        when(userR2dbcRepository.findLiveById(any(UUID.class)))
                .thenReturn(Mono.error(new RuntimeException("statement timeout")));

        StepVerifier.create(userRepository.getBusinessUserByUserId(UUID.randomUUID()))
                .expectError(TechnicalException.class)
                .verify();
    }

    @Test
    @DisplayName("a Keycloak failure while deleting a subject surfaces as KEYCLOAK_ERROR")
    void identityAdapter_shouldTranslateADeleteFailureToKeycloakError_whenKeycloakAnswers500() {
        when(keycloakAdminApi.logout(anyString())).thenReturn(Mono.empty());
        when(keycloakAdminApi.deleteUser(anyString())).thenReturn(Mono.error(
                WebClientResponseException.create(500, "Internal Server Error", null, null, null)));

        StepVerifier.create(identityProvider.deleteSubject("kc-1"))
                .expectErrorMatches(error -> error instanceof TechnicalException technical
                        && technical.getErrorCode() == TechnicalErrorCode.KEYCLOAK_ERROR)
                .verify();
    }

    @Test
    @DisplayName("Keycloak's 404 while deleting a subject is absorbed before the aspect turns it into USER_NOT_FOUND")
    void identityAdapter_shouldCompleteADelete_whenKeycloakAnswers404() {
        when(keycloakAdminApi.logout(anyString())).thenReturn(Mono.error(
                WebClientResponseException.create(404, "Not Found", null, null, null)));
        when(keycloakAdminApi.deleteUser(anyString())).thenReturn(Mono.error(
                WebClientResponseException.create(404, "Not Found", null, null, null)));

        StepVerifier.create(identityProvider.deleteSubject("kc-1")).verifyComplete();
    }

    @Test
    @DisplayName("the deletion job adapter is advised too")
    void accountDeletionAdapter_shouldTranslateToDatabaseError_whenTheQueryFails() {
        assertThat(AopUtils.isAopProxy(accountDeletionRepository)).isTrue();
        when(accountDeletionR2dbcRepository.claimDue(anyInt(), anyLong()))
                .thenReturn(Flux.error(new RuntimeException("connection reset by peer")));

        StepVerifier.create(accountDeletionRepository.claimDue(20))
                .expectErrorMatches(error -> error instanceof TechnicalException technical
                        && technical.getErrorCode() == TechnicalErrorCode.DATABASE_ERROR)
                .verify();
    }
}
