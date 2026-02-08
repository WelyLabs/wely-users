package com.calendar.users.infrastructure.identity.adapters;

import com.calendar.users.exception.TechnicalErrorCode;
import com.calendar.users.exception.TechnicalException;
import com.calendar.users.infrastructure.identity.api.KeycloakAdminApi;
import com.calendar.users.infrastructure.identity.models.KeycloakUserResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IdentityAuthAdapterTest {

    @Mock
    private KeycloakAdminApi keycloakAdminApi;

    @InjectMocks
    private IdentityAuthAdapter identityAuthAdapter;

    @Test
    void getUser_ShouldReturnKeycloakUserResponse() {
        // Given
        String keycloakId = "user-123";
        KeycloakUserResponse expectedResponse = new KeycloakUserResponse("testuser", "First", "Last",
                "test@example.com", true);
        when(keycloakAdminApi.getUser(keycloakId)).thenReturn(Mono.just(expectedResponse));

        // When
        Mono<KeycloakUserResponse> result = identityAuthAdapter.getUser(keycloakId);

        // Then
        StepVerifier.create(result)
                .expectNext(expectedResponse)
                .verifyComplete();
    }

    @Test
    void getUser_ShouldMapErrorToTechnicalException() {
        // Given
        String keycloakId = "user-123";
        when(keycloakAdminApi.getUser(keycloakId)).thenReturn(Mono.error(new RuntimeException("API Error")));

        // When
        Mono<KeycloakUserResponse> result = identityAuthAdapter.getUser(keycloakId);

        // Then
        StepVerifier.create(result)
                .expectErrorMatches(throwable -> throwable instanceof TechnicalException &&
                        ((TechnicalException) throwable).getErrorCode() == TechnicalErrorCode.KEYCLOAK_ERROR)
                .verify();
    }
}
