package com.calendar.users.application.rest;

import com.calendar.users.application.dtos.AvatarsRequest;
import com.calendar.users.domain.services.UserService;
import com.calendar.users.exception.GlobalErrorHandler;
import com.calendar.users.exception.TechnicalErrorCounter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AvatarControllerTest {

    @Mock
    private UserService userService;

    private WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        webTestClient = WebTestClient.bindToController(new AvatarController(userService))
                .controllerAdvice(new GlobalErrorHandler(new TechnicalErrorCounter(new SimpleMeterRegistry())))
                .build();
    }

    @Test
    void pictureUrls_shouldAnswerAUrlPerUserWithAPicture() {
        UUID camille = UUID.randomUUID();
        UUID lea = UUID.randomUUID();
        when(userService.pictureUrls(List.of(camille, lea))).thenReturn(Mono.just(Map.of(camille, "https://media/signed")));

        webTestClient.post()
                .uri("/avatars")
                .bodyValue(new AvatarsRequest(List.of(camille, lea)))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$['" + camille + "']").isEqualTo("https://media/signed")
                .jsonPath("$['" + lea + "']").doesNotExist();
    }

    @Test
    void pictureUrls_shouldRefuseMoreThanAHundredUsers() {
        List<UUID> tooMany = IntStream.range(0, 101).mapToObj(i -> UUID.randomUUID()).toList();

        webTestClient.post()
                .uri("/avatars")
                .bodyValue(new AvatarsRequest(tooMany))
                .exchange()
                .expectStatus().isBadRequest();

        verify(userService, never()).pictureUrls(any());
    }

    @Test
    void pictureUrls_shouldAnswerAnEmptyMap_forNoUsers() {
        when(userService.pictureUrls(List.of())).thenReturn(Mono.just(Collections.emptyMap()));

        webTestClient.post()
                .uri("/avatars")
                .bodyValue(new AvatarsRequest(List.of()))
                .exchange()
                .expectStatus().isOk()
                .expectBody().json("{}");
    }
}
