package com.calendar.users.application.rest;

import com.calendar.users.application.dtos.PreferencesRequest;
import com.calendar.users.application.dtos.PreferencesResponse;
import com.calendar.users.application.mappers.PreferencesRestMapper;
import com.calendar.users.domain.models.Theme;
import com.calendar.users.domain.models.UserPreferences;
import com.calendar.users.domain.services.UserService;
import com.calendar.users.exception.BusinessErrorCode;
import com.calendar.users.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.jwt.Jwt;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.UUID;

import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PreferencesControllerTest {

    private static final UUID ME = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Mock
    private UserService userService;

    @Mock
    private Jwt jwt;

    private PreferencesController controller;

    @BeforeEach
    void setUp() {
        controller = new PreferencesController(userService, Mappers.getMapper(PreferencesRestMapper.class));
        when(jwt.getClaimAsString("businessId")).thenReturn(ME.toString());
    }

    @Test
    void preferences_shouldReadTheCallersOwn() {
        when(userService.preferences(ME)).thenReturn(Mono.just(new UserPreferences(Theme.DARK)));

        StepVerifier.create(controller.preferences(jwt))
                .expectNext(new PreferencesResponse(Theme.DARK))
                .verifyComplete();
    }

    @Test
    void updatePreferences_shouldStoreTheChosenTheme() {
        when(userService.updatePreferences(ME, new UserPreferences(Theme.LIGHT)))
                .thenAnswer(call -> Mono.just(call.getArgument(1)));

        StepVerifier.create(controller.updatePreferences(jwt, new PreferencesRequest(Theme.LIGHT)))
                .expectNext(new PreferencesResponse(Theme.LIGHT))
                .verifyComplete();
    }

    @Test
    void updatePreferences_shouldPassOnAnUnknownUser() {
        when(userService.updatePreferences(ME, new UserPreferences(Theme.DARK)))
                .thenReturn(Mono.error(new BusinessException(BusinessErrorCode.USER_NOT_FOUND)));

        StepVerifier.create(controller.updatePreferences(jwt, new PreferencesRequest(Theme.DARK)))
                .expectErrorMatches(error -> error instanceof BusinessException exception
                        && exception.getErrorCode() == BusinessErrorCode.USER_NOT_FOUND)
                .verify();
    }
}
