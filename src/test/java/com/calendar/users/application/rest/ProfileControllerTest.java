package com.calendar.users.application.rest;

import com.calendar.users.domain.models.BusinessUser;
import com.calendar.users.domain.services.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.BindingContext;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.result.method.HandlerMethodArgumentResolver;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProfileControllerTest {

    @Mock
    private UserService userService;

    private WebTestClient webTestClient;
    private Jwt mockJwt;

    @BeforeEach
    void setUp() {
        mockJwt = mock(Jwt.class);

        webTestClient = WebTestClient.bindToController(new ProfileController(userService))
                .argumentResolvers(configurer -> configurer.addCustomResolver(new HandlerMethodArgumentResolver() {
                    @Override
                    public boolean supportsParameter(MethodParameter parameter) {
                        return parameter.getParameterType().equals(Jwt.class);
                    }

                    @Override
                    public Mono<Object> resolveArgument(MethodParameter parameter, BindingContext bindingContext,
                            ServerWebExchange exchange) {
                        return Mono.just(mockJwt);
                    }
                }))
                .build();
    }

    @Test
    void resolveInternalUserId_ShouldReturnInternalId() {
        // Given
        String kcId = "kc-123";
        UUID internalId = UUID.randomUUID();
        when(userService.resolveInternalUserId(kcId)).thenReturn(Mono.just(internalId));

        // When & Then
        webTestClient.get()
                .uri("/profile/resolve/{keycloakId}", kcId)
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .isEqualTo(internalId.toString());
    }

    @Test
    void readProfile_ShouldReturnUser() {
        // Given
        UUID userId = UUID.randomUUID();
        when(mockJwt.getClaimAsString("businessId")).thenReturn(userId.toString());

        BusinessUser user = new BusinessUser(userId, "user", 1, "F", "L", "url", LocalDateTime.now());
        when(userService.readProfile(userId)).thenReturn(Mono.just(user));

        // When & Then
        webTestClient.get()
                .uri("/profile")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.userName").isEqualTo("user");
    }

    @Test
    void updateProfilePicture_ShouldReturnUrl() {
        // Given
        Long userId = 123L;
        String profileUrl = "http://pic.url";
        when(userService.updateProfilePicture(eq(userId), any())).thenReturn(Mono.just(profileUrl));

        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", "test-content".getBytes()).filename("test.jpg");

        // When & Then
        webTestClient.post()
                .uri("/profile/picture")
                .header("X-Internal-User-Id", userId.toString())
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .isEqualTo(profileUrl);
    }
}
