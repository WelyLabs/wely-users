package com.calendar.users.application.rest;

import com.calendar.users.domain.services.AccountDeletionService;
import com.calendar.users.exception.GlobalErrorHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeletedUsersControllerTest {

    private static final UUID DELETED = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID LIVE = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Mock
    private AccountDeletionService accountDeletionService;

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToController(new DeletedUsersController(accountDeletionService))
                .controllerAdvice(new GlobalErrorHandler(new SimpleMeterRegistry()))
                .validator(new LocalValidatorFactoryBean())
                .build();
    }

    @Test
    void deletedUsers_shouldAnswerTheDeletedSubset_whenSomeIdsAreTombstones() {
        when(accountDeletionService.deletedAmong(List.of(DELETED, LIVE))).thenReturn(Mono.just(Set.of(DELETED)));

        client.post().uri("/users/deleted")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("userIds", List.of(DELETED, LIVE)))
                .exchange()
                .expectStatus().isOk()
                .expectBody().json("{\"deletedIds\":[\"" + DELETED + "\"]}", true);
    }

    @Test
    void deletedUsers_shouldAnswerAnEmptyList_whenNoneIsDeleted() {
        when(accountDeletionService.deletedAmong(List.of(LIVE))).thenReturn(Mono.just(Set.of()));

        client.post().uri("/users/deleted")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("userIds", List.of(LIVE)))
                .exchange()
                .expectStatus().isOk()
                .expectBody().json("{\"deletedIds\":[]}", true);
    }

    @Test
    void deletedUsers_shouldAnswer400_whenMoreThan100IdsAreSent() {
        List<UUID> tooMany = IntStream.range(0, 101).mapToObj(i -> UUID.randomUUID()).toList();

        client.post().uri("/users/deleted")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("userIds", tooMany))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.code").isEqualTo("USR-VAL-001");

        verify(accountDeletionService, never()).deletedAmong(any());
    }

    @Test
    void deletedUsers_shouldAnswer400_whenUserIdsIsMissing() {
        client.post().uri("/users/deleted")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchange()
                .expectStatus().isBadRequest();

        verify(accountDeletionService, never()).deletedAmong(any());
    }

    @Test
    void deletedUsers_shouldAnswer400_whenAnIdIsNotAUuid() {
        client.post().uri("/users/deleted")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"userIds\":[\"not-a-uuid\"]}")
                .exchange()
                .expectStatus().isBadRequest();

        verify(accountDeletionService, never()).deletedAmong(any());
    }
}
