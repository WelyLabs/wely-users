package com.calendar.users.infrastructure.observability;

import com.calendar.users.exception.BusinessErrorCode;
import com.calendar.users.exception.BusinessException;
import com.calendar.users.exception.TechnicalErrorCode;
import com.calendar.users.exception.TechnicalException;
import com.calendar.users.configuration.KeycloakClientConfig;
import com.sun.net.httpserver.HttpServer;
import io.r2dbc.spi.ConnectionFactories;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.client.InMemoryReactiveOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InfrastructureErrorAspectTest {

    @Mock private ProceedingJoinPoint joinPoint;
    @Mock private Signature signature;

    private InfrastructureErrorAspect aspect;

    @BeforeEach
    void setUp() {
        aspect = new InfrastructureErrorAspect();
        when(joinPoint.getSignature()).thenReturn(signature);
        when(signature.getName()).thenReturn("findIdByKeycloakId");
    }

    private static TechnicalErrorCode codeOf(Throwable error) {
        return ((TechnicalException) error).getErrorCode();
    }

    // --- persistence -------------------------------------------------------

    @Test
    @DisplayName("a database failure becomes DATABASE_ERROR")
    void translatePersistenceFailures_shouldMapToDatabaseError() throws Throwable {
        when(joinPoint.proceed()).thenReturn(Mono.error(new RuntimeException("connection reset")));

        StepVerifier.create((Mono<?>) aspect.translatePersistenceFailures(joinPoint))
                .expectErrorMatches(error -> error instanceof TechnicalException
                        && codeOf(error) == TechnicalErrorCode.DATABASE_ERROR)
                .verify(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("the database failure stays attached as the cause, so the incident log shows it")
    void translatePersistenceFailures_shouldKeepTheOriginalErrorAsCause() throws Throwable {
        RuntimeException original = new RuntimeException("connection reset");
        when(joinPoint.proceed()).thenReturn(Mono.error(original));

        StepVerifier.create((Mono<?>) aspect.translatePersistenceFailures(joinPoint))
                .expectErrorSatisfies(error -> assertThat(error.getCause()).isSameAs(original))
                .verify(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("the adapter's business rule passes through: a unique constraint is not an incident")
    void translatePersistenceFailures_shouldLetBusinessFailuresThrough() throws Throwable {
        BusinessException alreadyExists = new BusinessException(BusinessErrorCode.USER_ALREADY_EXISTS);
        when(joinPoint.proceed()).thenReturn(Mono.error(alreadyExists));

        // The adapter translates DataIntegrityViolationException before the aspect. Were
        // the aspect to rewrite it, a duplicate handle would answer 500 instead of 409.
        StepVerifier.create((Mono<?>) aspect.translatePersistenceFailures(joinPoint))
                .expectErrorMatches(error -> error == alreadyExists)
                .verify(Duration.ofSeconds(10));
    }

    @Test
    void translatePersistenceFailures_shouldNotRewrapTechnicalFailures() throws Throwable {
        TechnicalException already = new TechnicalException(TechnicalErrorCode.DATABASE_ERROR);
        when(joinPoint.proceed()).thenReturn(Mono.error(already));

        StepVerifier.create((Mono<?>) aspect.translatePersistenceFailures(joinPoint))
                .expectErrorMatches(error -> error == already)
                .verify(Duration.ofSeconds(10));
    }

    // --- identity ----------------------------------------------------------

    @Test
    @DisplayName("an unreachable Keycloak becomes KEYCLOAK_ERROR, not DATABASE_ERROR")
    void translateIdentityFailures_shouldMapToKeycloakError() throws Throwable {
        when(joinPoint.proceed()).thenReturn(Mono.error(new RuntimeException("503 from admin API")));

        // Two separate pointcuts exist for this: conflating the two incidents would make
        // a production diagnosis impossible.
        StepVerifier.create((Mono<?>) aspect.translateIdentityFailures(joinPoint))
                .expectErrorMatches(error -> error instanceof TechnicalException
                        && codeOf(error) == TechnicalErrorCode.KEYCLOAK_ERROR)
                .verify(Duration.ofSeconds(10));
    }

    @Test
    void translateIdentityFailures_shouldKeepTheOriginalErrorAsCause() throws Throwable {
        RuntimeException original = new RuntimeException("503 from admin API");
        when(joinPoint.proceed()).thenReturn(Mono.error(original));

        StepVerifier.create((Mono<?>) aspect.translateIdentityFailures(joinPoint))
                .expectErrorSatisfies(error -> assertThat(error.getCause()).isSameAs(original))
                .verify(Duration.ofSeconds(10));
    }

    // --- media -------------------------------------------------------------

    @Test
    @DisplayName("what MediaApiAdapter lets through becomes MEDIA_ERROR, with its cause")
    void translateMediaFailures_shouldMapToMediaErrorWithTheOriginalCause() throws Throwable {
        IllegalStateException original = new IllegalStateException("wely-media mapper failed");
        when(joinPoint.proceed()).thenReturn(Mono.error(original));

        StepVerifier.create((Mono<?>) aspect.translateMediaFailures(joinPoint))
                .expectErrorSatisfies(error -> {
                    assertThat(codeOf(error)).isEqualTo(TechnicalErrorCode.MEDIA_ERROR);
                    assertThat(error.getCause()).isSameAs(original);
                })
                .verify(Duration.ofSeconds(10));
    }

    // --- shape of the return value -----------------------------------------

    @Test
    void translatePersistenceFailures_shouldHandleFluxAsWell() throws Throwable {
        IllegalStateException original = new IllegalStateException("pool exhausted");
        when(joinPoint.proceed()).thenReturn(Flux.error(original));

        StepVerifier.create((Flux<?>) aspect.translatePersistenceFailures(joinPoint))
                .expectErrorSatisfies(error -> assertThat(error)
                        .isInstanceOf(TechnicalException.class)
                        .hasCause(original))
                .verify(Duration.ofSeconds(10));
    }

    @Test
    void translatePersistenceFailures_shouldLeaveSuccessfulResultsAlone() throws Throwable {
        when(joinPoint.proceed()).thenReturn(Mono.just("value"));

        @SuppressWarnings("unchecked")
        Mono<String> wrapped = (Mono<String>) aspect.translatePersistenceFailures(joinPoint);

        StepVerifier.create(wrapped)
                .expectNext("value")
                .expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void translatePersistenceFailures_shouldPassThroughNonReactiveReturns() throws Throwable {
        when(joinPoint.proceed()).thenReturn(42);

        assertThat(aspect.translatePersistenceFailures(joinPoint)).isEqualTo(42);
    }

    @Test
    @DisplayName("nothing is subscribed at assembly: the advice does not replay the query")
    void translatePersistenceFailures_shouldNotSubscribeEagerly() throws Throwable {
        Mono<String> lazy = Mono.fromSupplier(() -> {
            throw new AssertionError("the publisher must not be subscribed here");
        });
        when(joinPoint.proceed()).thenReturn(lazy);

        assertThat(aspect.translatePersistenceFailures(joinPoint)).isInstanceOf(Mono.class);
    }

    @Test
    @DisplayName("the pointcut declarations are what bind the advice to each adapter package")
    void pointcutDeclarations_shouldExistForBothAdapterPackages() {
        // Empty by construction: a @Pointcut method is a named selector, and Spring reads the
        // annotation, never the body. Called here because the convention is that every public
        // method has a test, and because deleting one silently unbinds the advice — which
        // InfrastructureErrorAspectWiringTest is what actually catches.
        InfrastructureErrorAspect aspect = new InfrastructureErrorAspect();

        assertThatCode(aspect::persistenceAdapterMethod).doesNotThrowAnyException();
        assertThatCode(aspect::identityAdapterMethod).doesNotThrowAnyException();
    }
    @Test
    @DisplayName("a 404 from the dependency is a business outcome, not an incident")
    void translate_shouldMapNotFoundToUserNotFound() throws Throwable {
        // Keycloak answering "no such user" was surfacing as USR-TEC-001 at 502 — the platform
        // declaring itself broken because the caller asked for someone who does not exist.
        WebClientResponseException notFound = WebClientResponseException.create(
                HttpStatus.NOT_FOUND.value(), "Not Found", HttpHeaders.EMPTY, new byte[0], null);
        when(joinPoint.proceed()).thenReturn(Mono.error(notFound));

        Object result = aspect.translateIdentityFailures(joinPoint);

        StepVerifier.create((Mono<?>) result)
                .expectErrorSatisfies(error -> assertThat(error)
                        .isInstanceOf(BusinessException.class)
                        .extracting(e -> ((BusinessException) e).getErrorCode())
                        .isEqualTo(BusinessErrorCode.USER_NOT_FOUND))
                .verify(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("any other dependency failure stays a technical incident")
    void translate_shouldKeepOtherFailuresTechnical() throws Throwable {
        WebClientResponseException serverError = WebClientResponseException.create(
                HttpStatus.INTERNAL_SERVER_ERROR.value(), "Boom", HttpHeaders.EMPTY, new byte[0], null);
        when(joinPoint.proceed()).thenReturn(Mono.error(serverError));

        Object result = aspect.translateIdentityFailures(joinPoint);

        StepVerifier.create((Mono<?>) result)
                .expectErrorSatisfies(error -> {
                    assertThat(error)
                            .isInstanceOf(TechnicalException.class)
                            .extracting(e -> ((TechnicalException) e).getErrorCode())
                            .isEqualTo(TechnicalErrorCode.KEYCLOAK_ERROR);
                    assertThat(error.getCause()).isSameAs(serverError);
                })
                .verify(Duration.ofSeconds(10));
    }


    // --- secrets in the logged cause ---------------------------------------
    //
    // The ERROR line logs the whole cause chain. These build the real driver and client
    // failures around the secrets this service holds — the PostgreSQL password, the Keycloak
    // client secret — and check that the printed chain never carries them: the R2DBC driver
    // names host and port only, and the client secret travels in the token request, never in
    // a message. User-info in a client's base URL does reach the chain (Reactor's checkpoint
    // prints it); logback-spring.xml masks that, see LogbackSpringXmlTest.

    private static final String SECRET = "s3cr3t";

    private static String stackTrace(Throwable error) {
        StringWriter out = new StringWriter();
        error.printStackTrace(new PrintWriter(out));
        return out.toString();
    }

    private Throwable translatedPersistenceFailure(Mono<?> failing) throws Throwable {
        when(joinPoint.proceed()).thenReturn(failing);
        return ((Mono<?>) aspect.translatePersistenceFailures(joinPoint))
                .materialize().block(Duration.ofSeconds(20)).getThrowable();
    }

    private Throwable translatedIdentityFailure(Mono<?> failing) throws Throwable {
        when(joinPoint.proceed()).thenReturn(failing);
        return ((Mono<?>) aspect.translateIdentityFailures(joinPoint))
                .materialize().block(Duration.ofSeconds(20)).getThrowable();
    }

    @Test
    @DisplayName("a PostgreSQL connection failure does not print the password of the connection string")
    void translatePersistenceFailures_shouldNotLeakTheDatabasePassword_whenPostgresIsUnreachable() throws Throwable {
        Mono<?> connect = Mono.from(ConnectionFactories
                .get("r2dbc:postgresql://wely:" + SECRET + "@127.0.0.1:1/users?connectTimeout=PT1S")
                .create());

        Throwable error = translatedPersistenceFailure(connect);

        assertThat(error).isInstanceOf(TechnicalException.class).hasCauseInstanceOf(RuntimeException.class);
        assertThat(stackTrace(error)).doesNotContain(SECRET);
    }

    private static WebClient keycloakAdminClient(String baseUrl, String tokenUri) {
        ClientRegistration registration = ClientRegistration.withRegistrationId("wely-users-api-client")
                .clientId("wely-users-api")
                .clientSecret(SECRET)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .tokenUri(tokenUri)
                .build();
        var registrations = new InMemoryReactiveClientRegistrationRepository(registration);
        KeycloakClientConfig config = new KeycloakClientConfig();
        return config.keycloakAdminWebClient(baseUrl, config.authorizedClientManager(
                registrations, new InMemoryReactiveOAuth2AuthorizedClientService(registrations)));
    }

    private static void assertNoClientSecret(Throwable error) {
        String basicCredentials = Base64.getEncoder()
                .encodeToString(("wely-users-api:" + SECRET).getBytes(StandardCharsets.UTF_8));

        assertThat(error).isInstanceOf(TechnicalException.class).hasCauseInstanceOf(RuntimeException.class);
        assertThat(stackTrace(error)).doesNotContain(SECRET).doesNotContain(basicCredentials);
    }

    @Test
    @DisplayName("an unreachable Keycloak token endpoint does not print the client secret")
    void translateIdentityFailures_shouldNotLeakTheClientSecret_whenKeycloakIsUnreachable() throws Throwable {
        WebClient client = keycloakAdminClient(
                "http://wely:" + SECRET + "@127.0.0.1:1/admin/realms/wely-realm",
                "http://127.0.0.1:1/token?client_secret=" + SECRET);

        assertNoClientSecret(translatedIdentityFailure(
                client.get().uri("/users?secret=" + SECRET).retrieve().bodyToMono(String.class)));
    }

    @Test
    @DisplayName("Keycloak refusing the client credentials does not print them")
    void translateIdentityFailures_shouldNotLeakTheClientSecret_whenKeycloakRefusesIt() throws Throwable {
        HttpServer keycloak = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        keycloak.createContext("/", exchange -> {
            byte[] body = "{\"error\":\"invalid_client\",\"error_description\":\"Invalid client credentials\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(401, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        keycloak.start();
        try {
            String base = "http://127.0.0.1:" + keycloak.getAddress().getPort();
            WebClient client = keycloakAdminClient(base + "/admin/realms/wely-realm", base + "/token");

            assertNoClientSecret(translatedIdentityFailure(
                    client.get().uri("/users").retrieve().bodyToMono(String.class)));
        } finally {
            keycloak.stop(0);
        }
    }
}
