package com.calendar.users.application.rest;

import com.calendar.users.application.mappers.FriendProfileRestMapperImpl;
import com.calendar.users.domain.models.FriendProfile;
import com.calendar.users.domain.services.UserService;
import com.calendar.users.exception.BusinessErrorCode;
import com.calendar.users.exception.BusinessException;
import com.calendar.users.exception.GlobalErrorHandler;
import com.calendar.users.exception.TechnicalErrorCode;
import com.calendar.users.exception.TechnicalException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.MethodParameter;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.BindingContext;
import org.springframework.web.reactive.result.method.HandlerMethodArgumentResolver;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.time.MonthDay;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserProfileControllerTest {

    private static final UUID VIEWER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID FRIEND = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final LocalDateTime JOINED = LocalDateTime.of(2026, 1, 15, 10, 30);

    @Mock
    private UserService userService;

    private WebTestClient webTestClient;
    private final Jwt jwt = mock(Jwt.class);

    @BeforeEach
    void setUp() {
        webTestClient = WebTestClient.bindToController(new UserProfileController(userService, new FriendProfileRestMapperImpl()))
                .controllerAdvice(new GlobalErrorHandler())
                .argumentResolvers(configurer -> configurer.addCustomResolver(new HandlerMethodArgumentResolver() {
                    @Override
                    public boolean supportsParameter(MethodParameter parameter) {
                        return parameter.getParameterType().equals(Jwt.class);
                    }

                    @Override
                    public Mono<Object> resolveArgument(MethodParameter parameter, BindingContext bindingContext,
                                                        ServerWebExchange exchange) {
                        return Mono.just(jwt);
                    }
                }))
                .build();
    }

    @Test
    void friendProfile_shouldAnswerTheProfileAsJson_whenTheyAreFriends() {
        when(jwt.getClaimAsString("businessId")).thenReturn(VIEWER.toString());
        when(userService.friendProfile(VIEWER, FRIEND)).thenReturn(Mono.just(
                new FriendProfile(FRIEND, "lea", 4242, "Léa", "Martin", JOINED, null, List.of(), MonthDay.of(3, 12))));

        webTestClient.get().uri("/users/{userId}/profile", FRIEND)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .json("""
                        {"id":"22222222-2222-2222-2222-222222222222","userName":"lea","hashtag":4242,
                         "firstName":"Léa","lastName":"Martin","joinedDate":"2026-01-15T10:30:00",
                         "bio":null,"interests":[],"birthday":{"month":3,"day":12}}
                        """, true);
    }

    @Test
    void friendProfile_shouldAnswer403NotAFriend_whenTheyAreNotFriends() {
        when(jwt.getClaimAsString("businessId")).thenReturn(VIEWER.toString());
        when(userService.friendProfile(VIEWER, FRIEND))
                .thenReturn(Mono.error(new BusinessException(BusinessErrorCode.NOT_A_FRIEND)));

        webTestClient.get().uri("/users/{userId}/profile", FRIEND)
                .exchange()
                .expectStatus().isForbidden()
                .expectBody().jsonPath("$.code").isEqualTo("USR-BUS-009");
    }

    @Test
    void friendProfile_shouldAnswer404_whenTheUserIsUnknown() {
        when(jwt.getClaimAsString("businessId")).thenReturn(VIEWER.toString());
        when(userService.friendProfile(VIEWER, FRIEND))
                .thenReturn(Mono.error(new BusinessException(BusinessErrorCode.USER_NOT_FOUND)));

        webTestClient.get().uri("/users/{userId}/profile", FRIEND)
                .exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.code").isEqualTo("USR-BUS-001");
    }

    @Test
    void friendProfile_shouldAnswer502_whenSocialIsUnavailable() {
        when(jwt.getClaimAsString("businessId")).thenReturn(VIEWER.toString());
        when(userService.friendProfile(VIEWER, FRIEND))
                .thenReturn(Mono.error(new TechnicalException(TechnicalErrorCode.SOCIAL_ERROR)));

        webTestClient.get().uri("/users/{userId}/profile", FRIEND)
                .exchange()
                .expectStatus().isEqualTo(502)
                .expectBody().jsonPath("$.code").isEqualTo("USR-TEC-005");
    }

    @Test
    void friendProfile_shouldAnswer400_whenTheIdIsNotAUuid() {
        webTestClient.get().uri("/users/not-a-uuid/profile")
                .exchange()
                .expectStatus().isBadRequest();

        verify(userService, never()).friendProfile(any(), any());
    }

    @Test
    void friendProfile_shouldAnswer410WithUsrBus012_whenTheUserDeletedTheirAccount() {
        when(jwt.getClaimAsString("businessId")).thenReturn(VIEWER.toString());
        when(userService.friendProfile(VIEWER, FRIEND))
                .thenReturn(Mono.error(new BusinessException(BusinessErrorCode.USER_DELETED)));

        webTestClient.get().uri("/users/{userId}/profile", FRIEND)
                .exchange()
                .expectStatus().isEqualTo(410)
                .expectBody().jsonPath("$.code").isEqualTo("USR-BUS-012");
    }
}
