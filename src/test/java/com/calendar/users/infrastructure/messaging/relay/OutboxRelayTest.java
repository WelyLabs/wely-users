package com.calendar.users.infrastructure.messaging.relay;

import com.calendar.users.exception.TechnicalErrorCode;
import com.calendar.users.exception.TechnicalException;
import com.calendar.users.infrastructure.messaging.adapters.KafkaOutboxDispatcher;
import com.calendar.users.infrastructure.persistence.adapters.R2dbcOutboxEventStoreAdapter;
import com.calendar.users.infrastructure.persistence.models.entities.OutboxEventRow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The relay's behaviour, driven by real time at a very short interval.
 *
 * <p>Deliberately not a {@code StepVerifier.withVirtualTime} test. Two of the four things
 * worth asserting here — that a failed pass does not kill the loop, and that {@code stop}
 * actually stops it — are properties of a long-lived subscription, and a virtual clock
 * would prove them for a sequence that is not the one running in production.
 *
 * <p>{@code Strictness.LENIENT}: the loop keeps running during a test, so which stubs each
 * one happens to reach depends on how many passes fit in the window.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OutboxRelayTest {

    private static final Duration FAST = Duration.ofMillis(20);
    private static final Duration RETENTION = Duration.ofDays(7);
    private static final int MAX_ATTEMPTS = 3;

    @Mock
    private R2dbcOutboxEventStoreAdapter outboxEventStore;

    @Mock
    private KafkaOutboxDispatcher dispatcher;

    @Mock
    private TransactionalOperator transactionalOperator;

    private OutboxRelay relay;

    @BeforeEach
    void passTransactionsThrough() {
        // The boundary itself is R2dbcTransactionBoundaryAdapter's subject, and a real one
        // is exercised in R2dbcOutboxEventStoreAdapterIntegrationTest.
        when(transactionalOperator.transactional(any(Mono.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(outboxEventStore.purgePublishedBefore(any(Instant.class))).thenReturn(Mono.just(0L));
        when(outboxEventStore.markPublished(any())).thenReturn(Mono.just(0L));
        when(outboxEventStore.recordFailure(anyLong(), anyString())).thenReturn(Mono.just(1L));
    }

    @AfterEach
    void stopRelay() {
        if (relay != null) {
            relay.stop();
        }
    }

    private void startRelay() {
        relay = new OutboxRelay(outboxEventStore, dispatcher, transactionalOperator,
                FAST, 10, MAX_ATTEMPTS, Duration.ofHours(1), RETENTION);
        relay.start();
    }

    private static OutboxEventRow row(long id, int attempts) {
        return new OutboxEventRow(id, UUID.randomUUID(), "USER_CREATED", "{}", attempts);
    }

    @Test
    @DisplayName("claimed events are dispatched and then marked published")
    void start_shouldPublishAndMarkWhatItClaimed() {
        when(outboxEventStore.claimPending(anyInt(), anyInt()))
                .thenReturn(Flux.just(row(1, 0), row(2, 0)))
                .thenReturn(Flux.empty());
        when(dispatcher.dispatch(any(OutboxEventRow.class))).thenReturn(Mono.empty());

        startRelay();

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                verify(outboxEventStore).markPublished(List.of(1L, 2L)));
    }

    @Test
    @DisplayName("the batch stops at the first failure, and the events before it still commit")
    void start_shouldCommitThePrefixAndStopAtTheFirstFailure() {
        // Sending past a failure would reorder events inside a Kafka partition, which is
        // the one ordering guarantee this design depends on.
        OutboxEventRow first = row(1, 0);
        OutboxEventRow failing = row(2, 0);
        OutboxEventRow behind = row(3, 0);

        when(outboxEventStore.claimPending(anyInt(), anyInt()))
                .thenReturn(Flux.just(first, failing, behind))
                .thenReturn(Flux.empty());
        when(dispatcher.dispatch(first)).thenReturn(Mono.empty());
        when(dispatcher.dispatch(failing))
                .thenReturn(Mono.error(new TechnicalException(TechnicalErrorCode.KAFKA_ERROR)));

        startRelay();

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            verify(outboxEventStore).markPublished(List.of(1L));
            verify(outboxEventStore).recordFailure(eq(2L), anyString());
        });

        // The event queued behind the failure is left for the next pass, untouched.
        verify(dispatcher, never()).dispatch(behind);
    }

    @Test
    @DisplayName("the failure is counted outside the claim, so the attempt actually sticks")
    void start_shouldRecordTheAttemptOutsideTheClaimTransaction() {
        // Inside the transaction the increment would roll back with everything else, and
        // attempts would never leave zero — the cap would never fire and one unsendable
        // event would block the queue forever.
        when(outboxEventStore.claimPending(anyInt(), anyInt()))
                .thenReturn(Flux.just(row(7, 1)))
                .thenReturn(Flux.empty());
        when(dispatcher.dispatch(any(OutboxEventRow.class)))
                .thenReturn(Mono.error(new IllegalStateException("broker refused the message")));

        startRelay();

        ArgumentCaptor<String> storedError = ArgumentCaptor.captor();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                verify(outboxEventStore).recordFailure(eq(7L), storedError.capture()));

        assertThat(storedError.getValue()).contains("broker refused the message");
        verify(transactionalOperator, atLeast(1)).transactional(any(Mono.class));
    }

    @Test
    @DisplayName("the cap is pushed down into the claim rather than filtered afterwards")
    void start_shouldAskTheStoreToSkipExhaustedEvents() {
        // Filtering in Java would still read the poison row first on every pass, and it
        // would still be at the head of the ORDER BY id.
        when(outboxEventStore.claimPending(anyInt(), anyInt())).thenReturn(Flux.empty());

        startRelay();

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                verify(outboxEventStore, atLeast(1)).claimPending(10, MAX_ATTEMPTS));
    }

    @Test
    @DisplayName("a failing pass does not kill the loop")
    void start_shouldKeepLoopingAfterAFailedPass() {
        // The failure this project has already hit twice: an unhandled error terminates the
        // sequence and the relay stops for good, silently. The first pass fails here; the
        // loop has to come back.
        when(outboxEventStore.claimPending(anyInt(), anyInt()))
                .thenReturn(Flux.error(new IllegalStateException("connection reset by peer")))
                .thenReturn(Flux.empty());

        startRelay();

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                verify(outboxEventStore, atLeast(3)).claimPending(anyInt(), anyInt()));
    }

    @Test
    @DisplayName("stop cancels the loop, and isRunning says so")
    void stop_shouldCancelTheLoop() {
        when(outboxEventStore.claimPending(anyInt(), anyInt())).thenReturn(Flux.empty());

        startRelay();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                verify(outboxEventStore, atLeast(2)).claimPending(anyInt(), anyInt()));

        relay.stop();
        assertThat(relay.isRunning()).isFalse();

        long afterStop = claimCount();
        await().during(Duration.ofMillis(300)).atMost(Duration.ofSeconds(2))
                .until(() -> claimCount() == afterStop);
    }

    @Test
    @DisplayName("published events older than the retention window are purged")
    void start_shouldPurgePastTheRetentionWindow() {
        when(outboxEventStore.claimPending(anyInt(), anyInt())).thenReturn(Flux.empty());

        startRelay();

        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.captor();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                verify(outboxEventStore, atLeast(1)).purgePublishedBefore(cutoff.capture()));

        assertThat(cutoff.getValue())
                .isCloseTo(Instant.now().minus(RETENTION), within(10, ChronoUnit.SECONDS));
    }

    private long claimCount() {
        return mockingDetails(outboxEventStore).getInvocations().stream()
                .filter(invocation -> "claimPending".equals(invocation.getMethod().getName()))
                .count();
    }
}
