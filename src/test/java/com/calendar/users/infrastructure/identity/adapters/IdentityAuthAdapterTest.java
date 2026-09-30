package com.calendar.users.infrastructure.identity.adapters;

import com.calendar.users.domain.models.IdentityUser;
import com.calendar.users.infrastructure.identity.api.KeycloakAdminApi;
import com.calendar.users.infrastructure.identity.mappers.IdentityUserMapper;
import com.calendar.users.infrastructure.identity.models.KeycloakUserResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
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
}
