package com.calendar.users.application.rest;

import com.calendar.users.application.mappers.DeletedUsersRestMapper;
import com.calendar.users.application.mappers.DeletedUsersRestMapperImpl;
import com.calendar.users.domain.models.DeletedUser;
import com.calendar.users.domain.models.DeletedUsersPage;
import com.calendar.users.domain.services.AccountDeletionService;
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

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InternalDeletedUsersControllerTest {

    private static final DeletedUser FIRST = new DeletedUser(
            UUID.fromString("11111111-1111-1111-1111-111111111111"), Instant.parse("2026-10-15T14:03:27.123456Z"));
    private static final DeletedUser SECOND = new DeletedUser(
            UUID.fromString("22222222-2222-2222-2222-222222222222"), Instant.parse("2026-10-15T15:00:00Z"));

    @Mock
    private AccountDeletionService accountDeletionService;

    private final DeletedUsersRestMapper mapper = new DeletedUsersRestMapperImpl();

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToController(new InternalDeletedUsersController(accountDeletionService, mapper))
                .controllerAdvice(new GlobalErrorHandler(new TechnicalErrorCounter(new SimpleMeterRegistry())))
                .build();
    }

    @Test
    void deletedUsers_shouldAnswerTheFirstPage_whenNoCursorIsGiven() {
        when(accountDeletionService.deletedUsers(isNull(), eq(500)))
                .thenReturn(Mono.just(new DeletedUsersPage(List.of(FIRST), null)));

        client.get().uri("/internal/deleted-users")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.items[0].userId").isEqualTo(FIRST.userId().toString())
                .jsonPath("$.items.length()").isEqualTo(1);
    }

    @Test
    void deletedUsers_shouldResumeAfterTheCursor_whenOneIsGiven() {
        when(accountDeletionService.deletedUsers(FIRST, 500))
                .thenReturn(Mono.just(new DeletedUsersPage(List.of(SECOND), null)));

        client.get().uri(builder -> builder.path("/internal/deleted-users")
                        .queryParam("after", mapper.encodeCursor(FIRST)).build())
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.items[0].userId").isEqualTo(SECOND.userId().toString());
    }

    @Test
    void deletedUsers_shouldReturnAnOpaqueNextCursor_whenMoreRemain() {
        when(accountDeletionService.deletedUsers(isNull(), eq(1)))
                .thenReturn(Mono.just(new DeletedUsersPage(List.of(FIRST), FIRST)));

        client.get().uri("/internal/deleted-users?limit=1")
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.next").isEqualTo(mapper.encodeCursor(FIRST));
    }

    @Test
    void deletedUsers_shouldAnswerANullNext_whenThePageIsTheLast() {
        when(accountDeletionService.deletedUsers(isNull(), eq(500)))
                .thenReturn(Mono.just(new DeletedUsersPage(List.of(), null)));

        client.get().uri("/internal/deleted-users")
                .exchange()
                .expectStatus().isOk()
                .expectBody().json("{\"items\":[],\"next\":null}", true);
    }

    @Test
    void deletedUsers_shouldClampTheLimitTo500_whenMoreIsAsked() {
        when(accountDeletionService.deletedUsers(isNull(), anyInt()))
                .thenReturn(Mono.just(new DeletedUsersPage(List.of(), null)));

        client.get().uri("/internal/deleted-users?limit=10000").exchange().expectStatus().isOk();

        verify(accountDeletionService).deletedUsers(null, 500);
    }

    @Test
    void deletedUsers_shouldClampTheLimitTo1_whenZeroIsAsked() {
        when(accountDeletionService.deletedUsers(isNull(), anyInt()))
                .thenReturn(Mono.just(new DeletedUsersPage(List.of(), null)));

        client.get().uri("/internal/deleted-users?limit=0").exchange().expectStatus().isOk();

        verify(accountDeletionService).deletedUsers(null, 1);
    }

    @Test
    void deletedUsers_shouldAnswer400WithUsrBus013_whenTheCursorIsMalformed() {
        client.get().uri("/internal/deleted-users?after=not-a-cursor")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.code").isEqualTo("USR-BUS-013");

        verify(accountDeletionService, never()).deletedUsers(any(), anyInt());
    }

    @Test
    void deletedUsers_shouldWriteDeletedAtAsAnIsoInstant_whenSerializing() {
        when(accountDeletionService.deletedUsers(isNull(), eq(500)))
                .thenReturn(Mono.just(new DeletedUsersPage(List.of(FIRST), null)));

        client.get().uri("/internal/deleted-users")
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.items[0].deletedAt").isEqualTo("2026-10-15T14:03:27.123456Z");
    }
}
