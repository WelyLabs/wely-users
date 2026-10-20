package com.calendar.users.exception;

import com.calendar.users.domain.ports.IdentityProvider;
import com.calendar.users.domain.ports.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.result.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.server.WebFilter;
import reactor.core.publisher.Mono;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

/**
 * Every error the real application answers, not only those a controller raises, comes out in
 * the shape {@link GlobalErrorHandler} defines.
 *
 * <p>{@link GlobalErrorHandlerTest} binds the advice alone, so it cannot see what else the
 * application context registers: Boot's own {@code ProblemDetailsExceptionHandler} at order 0,
 * Spring Security's entry point, Boot's {@code DefaultErrorWebExceptionHandler}. Any of them
 * answering first produces a body without {@code code} or {@code requestId}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class GlobalErrorHandlerWiringTest {

    private static final String REGISTRATION_PATH = "/user-service/profile/registration";
    /** Answered by the test-only filter below, before any handler is even looked up. */
    private static final String FILTER_FAILURE_PATH = "/user-service/filter-failure";

    @TestConfiguration
    static class FailingFilterConfig {

        @Bean
        WebFilter failingFilter() {
            return (exchange, chain) -> exchange.getRequest().getPath().value().equals(FILTER_FAILURE_PATH)
                    ? Mono.error(new TechnicalException(TechnicalErrorCode.DATABASE_ERROR))
                    : chain.filter(exchange);
        }
    }

    @Autowired
    private ApplicationContext context;

    @MockitoBean private ReactiveJwtDecoder jwtDecoder;
    // Mocked so no request reaches an absent PostgreSQL or Keycloak.
    @MockitoBean private UserRepository userRepository;
    @MockitoBean private IdentityProvider identityProvider;

    private WebTestClient anonymous() {
        return WebTestClient.bindToApplicationContext(context)
                .apply(springSecurity())
                .configureClient()
                .responseTimeout(Duration.ofSeconds(30))
                .build();
    }

    private WebTestClient authenticated() {
        return anonymous().mutateWith(mockJwt().jwt(jwt -> jwt.claim("businessId", "11111111-1111-1111-1111-111111111111")));
    }

    private static void assertOurProblem(WebTestClient.ResponseSpec response, int status, String code) {
        response.expectStatus().isEqualTo(status)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(status)
                .jsonPath("$.code").isEqualTo(code)
                .jsonPath("$.timestamp").exists()
                .jsonPath("$.requestId").value(requestId -> assertThat(requestId.toString()).isNotBlank());
    }

    // --- framework-raised errors ------------------------------------------------

    @Test
    @DisplayName("an unknown path answers our 404, not Spring's")
    void handleResponseStatusException_shouldAnswerOurProblem_whenThePathIsUnknown() {
        assertOurProblem(authenticated().get().uri("/user-service/no-such-path").exchange(),
                404, GlobalErrorHandler.REQUEST_REFUSED_CODE);
    }

    @Test
    void handleResponseStatusException_shouldAnswerOurProblem_whenTheMethodIsNotAllowed() {
        assertOurProblem(authenticated().patch().uri(REGISTRATION_PATH).exchange(),
                405, GlobalErrorHandler.REQUEST_REFUSED_CODE);
    }

    @Test
    void handleResponseStatusException_shouldAnswerOurProblem_whenTheContentTypeIsUnsupported() {
        assertOurProblem(authenticated().put().uri(REGISTRATION_PATH)
                        .contentType(MediaType.TEXT_PLAIN)
                        .bodyValue("1990-01-01")
                        .exchange(),
                415, GlobalErrorHandler.REQUEST_REFUSED_CODE);
    }

    @Test
    void handleResponseStatusException_shouldAnswerOurProblem_whenTheBodyIsUnreadable() {
        assertOurProblem(authenticated().put().uri(REGISTRATION_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue("{not json")
                        .exchange(),
                400, GlobalErrorHandler.REQUEST_REFUSED_CODE);
    }

    @Test
    void handleValidationException_shouldAnswerOurProblem_whenAFieldIsInvalid() {
        authenticated().put().uri(REGISTRATION_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.code").isEqualTo(GlobalErrorHandler.VALIDATION_FAILED_CODE)
                .jsonPath("$.errors.birthDate").exists()
                .jsonPath("$.requestId").exists();
    }

    // --- security ----------------------------------------------------------------

    @Test
    @DisplayName("a missing token answers our 401 and keeps the Bearer challenge")
    void authenticationEntryPoint_shouldAnswerOurProblem_whenNoTokenIsSent() {
        WebTestClient.ResponseSpec response = anonymous().get().uri("/user-service/profile").exchange();

        response.expectHeader().value(HttpHeaders.WWW_AUTHENTICATE, header -> assertThat(header).startsWith("Bearer"));
        assertOurProblem(response, 401, BusinessErrorCode.AUTHENTICATION_REQUIRED.getCode());
    }

    @Test
    @DisplayName("a wrong internal secret answers our 401 too")
    void internalSecretFilter_shouldAnswerOurProblem_whenTheSecretIsWrong() {
        assertOurProblem(anonymous().get().uri("/user-service/profile/resolve/keycloak-abc")
                        .header("X-Internal-Secret", "wrong-secret")
                        .exchange(),
                401, BusinessErrorCode.AUTHENTICATION_REQUIRED.getCode());
    }

    // --- errors outside controllers ------------------------------------------------

    @Test
    @DisplayName("a failure raised in a WebFilter answers our problem, not Boot's error JSON")
    void problemWebExceptionHandler_shouldAnswerOurProblem_whenAWebFilterFails() {
        assertOurProblem(authenticated().get().uri(FILTER_FAILURE_PATH).exchange(),
                502, TechnicalErrorCode.DATABASE_ERROR.getCode());
    }

    // --- request id from the gateway -------------------------------------------------

    private static final String GATEWAY_ID = "abcd1234-5678-90ef-abcd-1234567890ef";

    private static void assertCarriesTheGatewaysId(WebTestClient.ResponseSpec response) {
        response.expectHeader().valueEquals(RequestIds.REQUEST_ID_HEADER, GATEWAY_ID)
                .expectBody()
                .jsonPath("$.requestId").isEqualTo(GATEWAY_ID);
    }

    @Test
    @DisplayName("a controller-path problem answers under the id the gateway forwarded")
    void handleResponseStatusException_shouldUseTheGatewaysRequestId() {
        assertCarriesTheGatewaysId(authenticated().get().uri("/user-service/no-such-path")
                .header(RequestIds.REQUEST_ID_HEADER, GATEWAY_ID)
                .exchange());
    }

    @Test
    @DisplayName("a security refusal answers under the gateway's id too: the id filter runs before security")
    void authenticationEntryPoint_shouldUseTheGatewaysRequestId() {
        assertCarriesTheGatewaysId(anonymous().get().uri("/user-service/profile")
                .header(RequestIds.REQUEST_ID_HEADER, GATEWAY_ID)
                .exchange());
    }

    @Test
    void problemWebExceptionHandler_shouldUseTheGatewaysRequestId() {
        assertCarriesTheGatewaysId(authenticated().get().uri(FILTER_FAILURE_PATH)
                .header(RequestIds.REQUEST_ID_HEADER, GATEWAY_ID)
                .exchange());
    }

    @Test
    @DisplayName("a forged id is replaced, in the problem and on the response alike")
    void handleResponseStatusException_shouldReplaceAnInvalidRequestId() {
        authenticated().get().uri("/user-service/no-such-path")
                .header(RequestIds.REQUEST_ID_HEADER, "bad id!")
                .exchange()
                .expectHeader().value(RequestIds.REQUEST_ID_HEADER, header -> assertThat(header).isNotEqualTo("bad id!"))
                .expectBody()
                .jsonPath("$.requestId").value(requestId -> assertThat(requestId.toString())
                        .isNotBlank()
                        .isNotEqualTo("bad id!"));
    }

    // --- Boot's own problem-details advice ---------------------------------------------

    @Test
    @DisplayName("Boot's problem-details advice is not registered: spring.webflux.problemdetails.enabled stays off")
    void context_shouldNotRegisterBootsProblemDetailsAdvice() {
        assertThat(context.getBeansOfType(ResponseEntityExceptionHandler.class)).isEmpty();
    }
}
