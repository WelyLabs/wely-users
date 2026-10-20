package com.calendar.users.configuration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.calendar.users.exception.BusinessErrorCode;
import com.calendar.users.exception.GlobalErrorHandler;
import com.calendar.users.exception.TechnicalErrorCode;
import io.swagger.v3.oas.models.OpenAPI;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers {@link OpenApiConfig#openApi} and the two things that make the documentation useful:
 * that it answers without a token, and that it describes the paths the gateway actually routes.
 *
 * <p>The specification is reachable in-cluster and in dev only. The gateway forwards
 * {@code /api/v1/<service>/**} and nothing else, so none of these paths is exposed publicly.
 */
// A running server, not bindToApplicationContext: springdoc registers its routes on the web
// handler, and the context-bound client does not see them — the first version of this test 404ed
// for that reason, which masked the real defect the last test here guards against.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class OpenApiConfigTest {

    @Autowired
    private ApplicationContext context;

    @Value("${local.server.port}")
    private int port;

    private WebTestClient client() {
        return WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    @Test
    @DisplayName("the generated document names the service rather than the application class")
    void openApi_shouldDescribeTheService() {
        var info = context.getBean(OpenAPI.class).getInfo();

        assertThat(info.getTitle()).isEqualTo("wely-users");
        assertThat(info.getVersion()).isEqualTo("v1");
        assertThat(info.getDescription()).isNotBlank();
    }

    @Test
    @DisplayName("the specification answers without a token")
    void apiDocs_shouldBeReachableUnauthenticated() {
        client().get().uri("/v3/api-docs")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.info.title").isEqualTo("wely-users");
    }

    @Test
    @DisplayName("the Swagger UI and its assets load without a token")
    void swaggerUi_shouldBeReachableUnauthenticated() {
        client().get().uri("/v3/api-docs/swagger-config")
                .exchange()
                .expectStatus().isOk();

        client().get().uri("/webjars/swagger-ui/index.html")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    @DisplayName("the documented paths carry the service prefix the gateway strips")
    void apiDocs_shouldDocumentPrefixedPaths() {
        // The regression this guards: WebConfig used to prefix every @RestController on the
        // classpath, springdoc's own included. That moved the specification itself to
        // /user-service/v3/api-docs behind authentication, and /v3/api-docs answered 404.
        byte[] body = client().get().uri("/v3/api-docs")
                .exchange()
                .expectStatus().isOk()
                .expectBody().returnResult().getResponseBody();

        assertThat(body).isNotNull();
        assertThat(new String(body, java.nio.charset.StandardCharsets.UTF_8))
                .contains("\"/user-service/");
    }

    // --- error documentation -------------------------------------------------

    private static final String PROBLEM_JSON = "['application/problem+json']";
    private static final String PROBLEM_REF = "#/components/schemas/Problem";

    private WebTestClient.BodyContentSpec apiDocs() {
        return client().get().uri("/v3/api-docs")
                .exchange()
                .expectStatus().isOk()
                .expectBody();
    }

    @Test
    @DisplayName("the Problem schema documents every field an error body carries")
    void problemResponsesCustomizer_shouldRegisterTheProblemSchema() {
        apiDocs()
                .jsonPath("$.components.schemas.Problem.properties.type.format").isEqualTo("uri")
                .jsonPath("$.components.schemas.Problem.properties.status").exists()
                .jsonPath("$.components.schemas.Problem.properties.code.description").value(description ->
                        assertThat(description.toString()).contains("BUS", "VAL", "REQ", "TEC"))
                .jsonPath("$.components.schemas.Problem.properties.timestamp.format").isEqualTo("date-time")
                .jsonPath("$.components.schemas.Problem.properties.requestId").exists()
                .jsonPath("$.components.schemas.Problem.properties.errors").exists();
    }

    @Test
    @DisplayName("the code enum lists the service's codes, read from the enums and the handler")
    void problemResponsesCustomizer_shouldListEveryErrorCode() {
        apiDocs().jsonPath("$.components.schemas.Problem.properties.code.enum").value(codes ->
                assertThat(((List<?>) codes).stream().map(String::valueOf).toList())
                        .contains(BusinessErrorCode.USER_NOT_FOUND.getCode(),
                                TechnicalErrorCode.KEYCLOAK_ERROR.getCode(),
                                GlobalErrorHandler.VALIDATION_ERROR_CODE,
                                GlobalErrorHandler.REQUEST_ERROR_CODE,
                                GlobalErrorHandler.UNEXPECTED_ERROR_CODE)
                        .containsExactlyElementsOf(OpenApiConfig.errorCodes()));
    }

    @Test
    @DisplayName("every operation documents its 4XX and 5XX answers as problem+json")
    void problemResponsesCustomizer_shouldAddProblemResponsesToEveryOperation() {
        WebTestClient.BodyContentSpec docs = apiDocs();
        int[] operations = new int[1];
        docs.jsonPath("$.paths.*.*.responses").value(responses -> operations[0] = ((List<?>) responses).size());

        assertThat(operations[0]).isPositive();
        for (String range : List.of("4XX", "5XX")) {
            docs.jsonPath("$.paths.*.*.responses['" + range + "'].content" + PROBLEM_JSON + ".schema['$ref']")
                    .value(refs -> assertThat((List<?>) refs)
                            .hasSize(operations[0])
                            .allMatch(PROBLEM_REF::equals));
        }
    }
}
