package com.calendar.users.application.rest;

import com.calendar.users.application.dtos.AboutRequest;
import com.calendar.users.application.dtos.AboutResponse;
import com.calendar.users.application.mappers.AboutRestMapper;
import com.calendar.users.domain.models.UserAbout;
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

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AboutControllerTest {

    private static final UUID ME = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Mock
    private UserService userService;

    @Mock
    private Jwt jwt;

    private AboutController controller;

    @BeforeEach
    void setUp() {
        controller = new AboutController(userService, Mappers.getMapper(AboutRestMapper.class));
        // Lenient: a request refused by validation never gets as far as reading the token.
        lenient().when(jwt.getClaimAsString("businessId")).thenReturn(ME.toString());
    }

    @Test
    void about_shouldReadTheCallersOwn() {
        when(userService.about(ME)).thenReturn(Mono.just(new UserAbout("Partante", List.of("Rando"))));

        StepVerifier.create(controller.about(jwt))
                .expectNext(new AboutResponse("Partante", List.of("Rando")))
                .expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void updateAbout_shouldStoreTheTidiedPresentation() {
        when(userService.updateAbout(ME, new UserAbout("Partante", List.of("Rando"))))
                .thenAnswer(call -> Mono.just(call.getArgument(1)));

        StepVerifier.create(controller.updateAbout(jwt, new AboutRequest("  Partante ", List.of("Rando", "rando"))))
                .expectNext(new AboutResponse("Partante", List.of("Rando")))
                .expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void updateAbout_shouldRefuseTooManyInterests_beforeCallingTheService() {
        StepVerifier.create(controller.updateAbout(jwt, new AboutRequest(null, List.of("a", "b", "c", "d", "e", "f"))))
                .expectErrorMatches(error -> error instanceof BusinessException exception
                        && exception.getErrorCode() == BusinessErrorCode.ABOUT_INVALID)
                .verify(Duration.ofSeconds(10));
        verify(userService, never()).updateAbout(any(), any());
    }

    @Test
    void about_shouldAnswer410_whenTheCallerIsATombstone() {
        when(userService.about(ME)).thenReturn(Mono.error(new BusinessException(BusinessErrorCode.USER_DELETED)));

        StepVerifier.create(controller.about(jwt))
                .expectErrorMatches(error -> error instanceof BusinessException exception
                        && exception.getErrorCode() == BusinessErrorCode.USER_DELETED)
                .verify(Duration.ofSeconds(10));
    }

    @Test
    void updateAbout_shouldAnswer410_whenTheCallerIsATombstone() {
        when(userService.updateAbout(any(), any()))
                .thenReturn(Mono.error(new BusinessException(BusinessErrorCode.USER_DELETED)));

        StepVerifier.create(controller.updateAbout(jwt, new AboutRequest("Partante", List.of())))
                .expectErrorMatches(error -> error instanceof BusinessException exception
                        && exception.getErrorCode() == BusinessErrorCode.USER_DELETED)
                .verify(Duration.ofSeconds(10));
    }
}
