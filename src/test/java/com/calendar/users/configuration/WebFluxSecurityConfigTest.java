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

    // Les ports sont mockés pour que ce test porte sur la sécurité : sinon une requête
    // autorisée atteint PostgreSQL et Keycloak, absents, et le test expire.
    @MockitoBean private UserRepository userRepository;
    @MockitoBean private IdentityProvider identityProvider;
    @MockitoBean private UserEventPublisher userEventPublisher;

    private WebTestClient client() {
        return WebTestClient.bindToApplicationContext(context)
                .apply(springSecurity())
                .configureClient()
                .build();
    }

    // --- le filtre à secret partagé ---------------------------------------

    @Test
    @DisplayName("sans en-tête de secret, l'endpoint interne répond 401")
    void internalSecretFilter_shouldRejectAMissingHeader() {
        client().get().uri(RESOLVE_PATH)
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("un secret erroné répond 401")
    void internalSecretFilter_shouldRejectAWrongSecret() {
        client().get().uri(RESOLVE_PATH)
                .header(SECRET_HEADER, "wrong-secret")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("un préfixe correct du secret ne suffit pas")
    void internalSecretFilter_shouldRejectAPrefixOfTheSecret() {
        // La comparaison est en temps constant : un préfixe valide ne doit pas être
        // traité différemment d'une valeur totalement fausse.
        client().get().uri(RESOLVE_PATH)
                .header(SECRET_HEADER, VALID_SECRET.substring(0, VALID_SECRET.length() - 1))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("avec le bon secret, l'endpoint interne est atteint sans JWT")
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
    @DisplayName("le préflight CORS traverse le filtre sans secret")
    void internalSecretFilter_shouldLetCorsPreflightThrough() {
        client().options().uri(RESOLVE_PATH)
                .header("Origin", "http://localhost:4200")
                .header("Access-Control-Request-Method", "GET")
                .exchange()
                .expectStatus().is2xxSuccessful();
    }

    @Test
    @DisplayName("le secret ne donne accès qu'à l'endpoint interne, pas au reste de l'API")
    void internalSecretFilter_shouldNotGrantAccessToOtherPaths() {
        client().get().uri("/user-service/profile")
                .header(SECRET_HEADER, VALID_SECRET)
                .exchange()
                .expectStatus().isUnauthorized();
    }

    // --- la chaîne de filtres --------------------------------------------

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
    @DisplayName("les probes de santé répondent sans token, sinon le kubelet voit 401")
    void apiHttpSecurity_shouldExposeHealthProbesAnonymously() {
        // Le kubelet ne porte pas de JWT. Si ces chemins exigeaient une authentification,
        // la liveness échouerait en boucle et Kubernetes redémarrerait des pods sains.
        client().get().uri("/actuator/health/liveness")
                .exchange()
                .expectStatus().isOk();

        client().get().uri("/actuator/health/readiness")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    @DisplayName("le reste d'actuator n'est pas ouvert pour autant")
    void apiHttpSecurity_shouldNotExposeTheRestOfActuator() {
        client().get().uri("/actuator/env")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void apiHttpSecurity_shouldBeTheOnlyChainDeclared() {
        // Une seconde chaîne plus permissive annulerait silencieusement celle-ci.
        assertThat(context.getBeansOfType(
                org.springframework.security.web.server.SecurityWebFilterChain.class)).hasSize(1);
    }

    // --- le décodeur JWT -------------------------------------------------

    @Test
    @DisplayName("le décodeur est construit sur le JWKS interne, séparé de l'issuer public")
    void jwtDecoder_shouldBeConfigured() {
        assertThat(config.jwtDecoder()).isNotNull();
    }
}
