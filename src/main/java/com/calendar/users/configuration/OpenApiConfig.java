package com.calendar.users.configuration;

import com.calendar.users.exception.BusinessErrorCode;
import com.calendar.users.exception.GlobalErrorHandler;
import com.calendar.users.exception.TechnicalErrorCode;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/**
 * Names the API that springdoc generates from the controllers, and documents how it fails.
 *
 * <p>Without this the document is titled after the application and says nothing about what the
 * service is for. The operations themselves are derived from the handler signatures, so what is
 * worth writing by hand is the part no signature carries: the service's purpose, and the
 * {@code application/problem+json} body every error answers with.
 */
@Configuration
public class OpenApiConfig {

    static final String PROBLEM_SCHEMA = "Problem";
    static final String PROBLEM_MEDIA_TYPE = "application/problem+json";
    static final String CLIENT_ERRORS = "4XX";
    static final String SERVER_ERRORS = "5XX";

    private static final String PROBLEM_REF = "#/components/schemas/" + PROBLEM_SCHEMA;
    private static final String CODE_DESCRIPTION = """
            Stable identifier a client branches on. Families: \
            BUS — a business rule refused the request, change the request; \
            VAL — some fields are invalid, see `errors`; \
            REQ — the framework refused the request (unknown path, wrong method, unreadable body); \
            TEC — a dependency failed, retry later: this is an incident.""";

    @Bean
    public OpenAPI openApi() {
        return new OpenAPI().info(new Info()
                .title("wely-users")
                .version("v1")
                .description("User profiles and identity resolution. Translates a Keycloak subject into a business identifier, and publishes USER_CREATED."));
    }

    /**
     * Registers the {@code Problem} schema and declares it as the {@code 4XX} and {@code 5XX}
     * answer of every operation that does not already document its own.
     */
    @Bean
    public OpenApiCustomizer problemResponsesCustomizer() {
        return openApi -> {
            Components components = openApi.getComponents() == null ? new Components() : openApi.getComponents();
            openApi.setComponents(components.addSchemas(PROBLEM_SCHEMA, problemSchema()));

            if (openApi.getPaths() == null) {
                return;
            }
            openApi.getPaths().values().forEach(path -> path.readOperations().forEach(operation -> {
                ApiResponses responses = operation.getResponses() == null ? new ApiResponses() : operation.getResponses();
                responses.computeIfAbsent(CLIENT_ERRORS, status -> problemResponse("The request was refused."));
                responses.computeIfAbsent(SERVER_ERRORS, status -> problemResponse("The request could not be completed."));
                operation.setResponses(responses);
            }));
        };
    }

    /** Every code this service can answer with, read from the enums so the list cannot drift. */
    static List<String> errorCodes() {
        return Stream.of(
                        Arrays.stream(BusinessErrorCode.values()).map(BusinessErrorCode::getCode),
                        Arrays.stream(TechnicalErrorCode.values()).map(TechnicalErrorCode::getCode),
                        Stream.of(GlobalErrorHandler.VALIDATION_ERROR_CODE,
                                GlobalErrorHandler.REQUEST_ERROR_CODE,
                                GlobalErrorHandler.UNEXPECTED_ERROR_CODE))
                .flatMap(codes -> codes)
                .distinct()
                .sorted()
                .toList();
    }

    private static Schema<Object> problemSchema() {
        Schema<String> code = typed("string", null);
        code.setDescription(CODE_DESCRIPTION);
        code.setEnum(errorCodes());

        Schema<Object> errors = typed("object", null);
        errors.setDescription("Invalid fields and why, by field name. Present on VAL codes only.");
        errors.setAdditionalProperties(typed("string", null));

        Schema<Object> problem = typed("object", null);
        problem.setDescription("RFC 7807 problem detail, the body of every error this service answers.");
        problem.addProperty("type", typed("string", "uri"));
        problem.addProperty("title", typed("string", null));
        problem.addProperty("status", typed("integer", "int32"));
        problem.addProperty("detail", typed("string", null));
        problem.addProperty("instance", typed("string", "uri"));
        problem.addProperty("code", code);
        problem.addProperty("timestamp", typed("string", "date-time"));
        problem.addProperty("requestId", described(typed("string", null),
                "WebFlux request id, also printed on the service's log lines for this request."));
        problem.addProperty("errors", errors);
        return problem;
    }

    private static ApiResponse problemResponse(String description) {
        return new ApiResponse()
                .description(description)
                .content(new Content().addMediaType(PROBLEM_MEDIA_TYPE,
                        new MediaType().schema(new Schema<>().$ref(PROBLEM_REF))));
    }

    /** Sets both type fields: OpenAPI 3.0 serializes {@code type}, 3.1 serializes {@code types}. */
    private static <T> Schema<T> typed(String type, String format) {
        Schema<T> schema = new Schema<>();
        schema.setType(type);
        schema.addType(type);
        schema.setFormat(format);
        return schema;
    }

    private static <T> Schema<T> described(Schema<T> schema, String description) {
        schema.setDescription(description);
        return schema;
    }
}
