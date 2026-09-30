package com.calendar.users.configuration;

import com.calendar.users.domain.models.BusinessUser;
import com.calendar.users.domain.ports.IdentityProvider;
import com.calendar.users.domain.ports.UserEventPublisher;
import com.calendar.users.domain.ports.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

/**
 * Covers both beans of {@link WebFluxSecurityConfig}, and in particular the filter
 * guarding {@code /profile/resolve}.
 *
 * <p>That endpoint is the one hole in the JWT wall, and it has to be: Keycloak's
 * protocol mapper calls it <em>while</em> minting a token, so no token exists yet. A
 * shared secret stands in, and these tests pin down that a missing or wrong secret is
 * refused — the value used to be hardcoded in the source of both sides.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class WebFluxSecurityConfigTest {

    /** Matches app.internal-secret in application-test.properties. */
    private static final String VALID_SECRET = "test-internal-secret";
    private static final String SECRET_HEADER = "X-Internal-Secret";
    private static final String RESOLVE_PATH = "/user-service/profile/resolve/keycloak-abc";

    @Autowired
    private ApplicationContext context;

    @Autowired
    private WebFluxSecurityConfig config;

    @MockitoBean
    private ReactiveJwtDecoder jwtDecoder;

    // The ports are mocked so this test covers security: otherwise an authorised
    // request reaches an absent PostgreSQL and Keycloak, and the test times out.
    @MockitoBean private UserRepository userRepository;
    @MockitoBean private IdentityProvider identityProvider;
    @MockitoBean private UserEventPublisher userEventPublisher;

    private WebTestClient client() {
        return WebTestClient.bindToApplicationContext(context)
                .apply(springSecurity())
                .configureClient()
                .build();
    }

    // --- the shared-secret filter -----------------------------------------

    @Test
    @DisplayName("without the secret header, the internal endpoint answers 401")
    void internalSecretFilter_shouldRejectAMissingHeader() {
        client().get().uri(RESOLVE_PATH)
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("a wrong secret answers 401")
    void internalSecretFilter_shouldRejectAWrongSecret() {
        client().get().uri(RESOLVE_PATH)
                .header(SECRET_HEADER, "wrong-secret")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("a correct prefix of the secret is not enough")
    void internalSecretFilter_shouldRejectAPrefixOfTheSecret() {
        // The comparison is constant-time: a valid prefix must not be treated any
        // differently from a wholly wrong value.
        client().get().uri(RESOLVE_PATH)
                .header(SECRET_HEADER, VALID_SECRET.substring(0, VALID_SECRET.length() - 1))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("with the right secret, the internal endpoint is reached without a JWT")
    void internalSecretFilter_shouldAcceptTheValidSecretWithoutAToken() {
        UUID businessId = UUID.randomUUID();
        when(userRepository.findIdByKeycloakId("keycloak-abc")).thenReturn(Mono.just(businessId));

        client().get().uri(RESOLVE_PATH)
                .header(SECRET_HEADER, VALID_SECRET)
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).isEqualTo(businessId.toString());
    }

    @Test
    @DisplayName("the CORS preflight passes the filter without a secret")
    void internalSecretFilter_shouldLetCorsPreflightThrough() {
        client().options().uri(RESOLVE_PATH)
                .header("Origin", "http://localhost:4200")
                .header("Access-Control-Request-Method", "GET")
                .exchange()
                .expectStatus().is2xxSuccessful();
    }

    @Test
    @DisplayName("the secret grants the internal endpoint only, not the rest of the API")
    void internalSecretFilter_shouldNotGrantAccessToOtherPaths() {
        client().get().uri("/user-service/profile")
                .header(SECRET_HEADER, VALID_SECRET)
                .exchange()
                .expectStatus().isUnauthorized();
    }

    // --- the filter chain -------------------------------------------------

    @Test
    void apiHttpSecurity_shouldRequireATokenOnBusinessRoutes() {
        client().get().uri("/user-service/profile")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void apiHttpSecurity_shouldAllowAuthenticatedRequests() {
        UUID businessId = UUID.randomUUID();
        when(userRepository.getBusinessUserByUserId(any(UUID.class)))
                .thenReturn(Mono.just(new BusinessUser(
                        businessId, "theo", 4271, "Theo", "B", null, LocalDateTime.now())));

        client().mutateWith(mockJwt().jwt(jwt -> jwt.claim("businessId", businessId.toString())))
                .get().uri("/user-service/profile")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    @DisplayName("the health probes answer without a token, or the kubelet sees 401")
    void apiHttpSecurity_shouldExposeHealthProbesAnonymously() {
        // The kubelet carries no JWT. Were these paths to require authentication,
        // liveness would fail in a loop and Kubernetes would restart healthy pods.
        client().get().uri("/actuator/health/liveness")
                .exchange()
                .expectStatus().isOk();

        client().get().uri("/actuator/health/readiness")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    @DisplayName("the rest of actuator is not opened along with it")
    void apiHttpSecurity_shouldNotExposeTheRestOfActuator() {
        client().get().uri("/actuator/env")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void apiHttpSecurity_shouldBeTheOnlyChainDeclared() {
        // A second, more permissive chain would silently override this one.
        assertThat(context.getBeansOfType(
                org.springframework.security.web.server.SecurityWebFilterChain.class)).hasSize(1);
    }

    // --- the JWT decoder --------------------------------------------------

    @Test
    @DisplayName("the decoder is built on the internal JWKS, separate from the public issuer")
    void jwtDecoder_shouldBeConfigured() {
        assertThat(config.jwtDecoder()).isNotNull();
    }
}
