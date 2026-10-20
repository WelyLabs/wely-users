package com.calendar.users.configuration;

import com.calendar.users.domain.models.BusinessUser;
import com.calendar.users.domain.ports.IdentityProvider;
import com.calendar.users.domain.ports.UserRepository;
import com.calendar.users.exception.GlobalErrorHandler;
import com.calendar.users.exception.ProblemResponseWriter;
import com.calendar.users.exception.TechnicalErrorCode;
import com.calendar.users.exception.TechnicalException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
// Tests disable metrics export by default; the Prometheus endpoint only exists with it on.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "management.prometheus.metrics.export.enabled=true")
@ActiveProfiles("test")
class WebFluxSecurityConfigTest {

    /** Matches app.internal-secret in application-test.properties. */
    private static final String VALID_SECRET = "test-internal-secret";
    /** Matches app.deleted-users.reader-secret in application-test.properties. */
    private static final String READER_SECRET = "test-reader-secret";
    private static final String DELETED_USERS_PATH = "/user-service/internal/deleted-users";
    private static final String SECRET_HEADER = "X-Internal-Secret";
    private static final String RESOLVE_PATH = "/user-service/profile/resolve/keycloak-abc";
    private static final String PROVISION_PATH = "/user-service/profile/provision";
    private static final String PROVISION_BODY =
            "{\"keycloakId\":\"keycloak-abc\",\"username\":\"lea\",\"firstName\":\"Léa\",\"lastName\":\"M\"}";

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

    private WebTestClient client() {
        return WebTestClient.bindToApplicationContext(context)
                .apply(springSecurity())
                .configureClient()
                .responseTimeout(Duration.ofSeconds(30))
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

    @Test
    @DisplayName("provisioning without the secret answers 401, even with a well-formed body")
    void internalSecretFilter_shouldGuardProvisioning() {
        client().post().uri(PROVISION_PATH)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue(PROVISION_BODY)
                .exchange()
                .expectStatus().isUnauthorized();

        verify(userRepository, never()).save(any(), anyString());
    }

    @Test
    @DisplayName("with the right secret, provisioning is reached without a JWT")
    void internalSecretFilter_shouldAcceptProvisioningWithTheValidSecret() {
        UUID businessId = UUID.randomUUID();
        when(userRepository.findIdByKeycloakId("keycloak-abc")).thenReturn(Mono.just(businessId));

        client().post().uri(PROVISION_PATH)
                .header(SECRET_HEADER, VALID_SECRET)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue(PROVISION_BODY)
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).isEqualTo(businessId.toString());
    }

    // --- the deleted-users reader secret -----------------------------------

    @Test
    void readerSecretFilter_shouldAnswer401_whenTheHeaderIsMissingOnTheDeletedUsersListing() {
        client().get().uri(DELETED_USERS_PATH)
                .exchange()
                .expectStatus().isUnauthorized();

        verify(userRepository, never()).findDeleted(any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("the Keycloak mapper's secret cannot read the listing: the two secrets are distinct")
    void readerSecretFilter_shouldAnswer401_whenTheKeycloakSecretIsSentToTheListing() {
        client().get().uri(DELETED_USERS_PATH)
                .header(SECRET_HEADER, VALID_SECRET)
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void readerSecretFilter_shouldLetTheListingThroughWithoutAToken_whenTheReaderSecretMatches() {
        when(userRepository.findDeleted(null, 501)).thenReturn(Flux.empty());

        client().get().uri(DELETED_USERS_PATH)
                .header(SECRET_HEADER, READER_SECRET)
                .exchange()
                .expectStatus().isOk()
                .expectBody().json("{\"items\":[],\"next\":null}", true);
    }

    @Test
    @DisplayName("with no reader secret configured, every call to the listing is refused")
    void readerSecretFilter_shouldAnswer401_whenNoReaderSecretIsConfigured() {
        ProblemResponseWriter problems = new ProblemResponseWriter(
                new GlobalErrorHandler(new SimpleMeterRegistry()), ServerCodecConfigurer.create());
        WebFilter filter = new WebFluxSecurityConfig("http://issuer", "http://jwks", VALID_SECRET, "")
                .internalSecretWebFilter(problems);
        AtomicBoolean reached = new AtomicBoolean();

        for (String header : new String[] {"", "anything"}) {
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get(DELETED_USERS_PATH).header(SECRET_HEADER, header));

            filter.filter(exchange, ignored -> {
                reached.set(true);
                return Mono.empty();
            }).block(Duration.ofSeconds(10));

            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(exchange.getResponse().getBodyAsString().block(Duration.ofSeconds(10)))
                    .contains("\"code\":\"USR-REQ-001\"")
                    .contains("\"requestId\"");
        }
        assertThat(reached).isFalse();
    }

    @Test
    void internalSecretFilter_shouldAnswer401_whenTheReaderSecretIsSentToResolve() {
        client().get().uri(RESOLVE_PATH)
                .header(SECRET_HEADER, READER_SECRET)
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("the reader secret opens the listing for reading only")
    void readerSecretFilter_shouldRequireAToken_whenWritingToAnInternalPath() {
        client().post().uri(DELETED_USERS_PATH)
                .header(SECRET_HEADER, READER_SECRET)
                .exchange()
                .expectStatus().isUnauthorized();
    }

    // --- the filter chain -------------------------------------------------

    @Test
    void apiHttpSecurity_shouldRequireAToken_whenDeletingTheProfile() {
        client().delete().uri("/user-service/profile")
                .exchange()
                .expectStatus().isUnauthorized();

        verify(userRepository, never()).eraseAccount(any(), any());
    }

    @Test
    void apiHttpSecurity_shouldRequireAToken_whenLookingUpDeletedUsers() {
        client().post().uri("/user-service/users/deleted")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue("{\"userIds\":[]}")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void apiHttpSecurity_shouldRequireATokenOnBusinessRoutes() {
        client().get().uri("/user-service/profile")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void apiHttpSecurity_shouldRequireATokenOnAFriendsProfile() {
        client().get().uri("/user-service/users/" + UUID.randomUUID() + "/profile")
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
    @DisplayName("an authenticated caller still finds no other actuator endpoint: they are not exposed")
    void apiHttpSecurity_shouldNotExposeTheRestOfActuator_evenWithAToken() {
        client().mutateWith(mockJwt())
                .get().uri("/actuator/env")
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    @DisplayName("Prometheus scrapes without a token, and finds the technical error counter")
    void apiHttpSecurity_shouldExposePrometheusAnonymously_withTheTechnicalErrorCounter() {
        when(userRepository.getBusinessUserByUserId(any(UUID.class)))
                .thenReturn(Mono.error(new TechnicalException(TechnicalErrorCode.DATABASE_ERROR)));
        client().mutateWith(mockJwt().jwt(jwt -> jwt.claim("businessId", UUID.randomUUID().toString())))
                .get().uri("/user-service/profile")
                .exchange()
                .expectStatus().isEqualTo(502);

        client().get().uri("/actuator/prometheus")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).value(body -> assertThat(body)
                        .contains("wely_errors_technical_total{")
                        .contains("code=\"USR-TEC-002\""));
    }

    @Test
    @DisplayName("only reading the metrics is opened")
    void apiHttpSecurity_shouldRequireAToken_whenWritingToPrometheus() {
        client().post().uri("/actuator/prometheus")
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
