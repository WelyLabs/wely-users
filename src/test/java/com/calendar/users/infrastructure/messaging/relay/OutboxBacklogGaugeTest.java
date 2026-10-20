package com.calendar.users.infrastructure.messaging.relay;

import com.calendar.users.exception.GlobalErrorHandler;
import com.calendar.users.exception.TechnicalErrorCode;
import com.calendar.users.exception.TechnicalErrorCounter;
import com.calendar.users.exception.TechnicalException;
import com.calendar.users.infrastructure.persistence.adapters.R2dbcOutboxEventStoreAdapter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OutboxBacklogGaugeTest {

    private final R2dbcOutboxEventStoreAdapter outboxEventStore = mock(R2dbcOutboxEventStoreAdapter.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private OutboxBacklogGauge gauge;

    private OutboxBacklogGauge gauge(Duration refreshInterval) {
        gauge = new OutboxBacklogGauge(outboxEventStore, new TechnicalErrorCounter(meterRegistry), meterRegistry,
                refreshInterval);
        return gauge;
    }

    @AfterEach
    void stopGauge() {
        if (gauge != null) {
            gauge.stop();
        }
    }

    private double pending() {
        return meterRegistry.get("wely.outbox.pending").tag("outbox", "outbox_event").gauge().value();
    }

    private double technicalErrors(String code) {
        var counter = meterRegistry.find(GlobalErrorHandler.TECHNICAL_ERRORS_METRIC)
                .tags(GlobalErrorHandler.CODE_TAG, code, GlobalErrorHandler.SOURCE_TAG, GlobalErrorHandler.SOURCE_JOB)
                .counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void refreshOnce_shouldExposeTheCountQueryResult() {
        when(outboxEventStore.countPending()).thenReturn(Mono.just(42L));
        OutboxBacklogGauge backlog = gauge(Duration.ofHours(1));

        StepVerifier.create(backlog.refreshOnce()).expectNext(42L).expectComplete().verify(Duration.ofSeconds(10));

        assertThat(pending()).isEqualTo(42.0);
    }

    @Test
    void refreshOnce_shouldKeepTheLastValueAndCountTheFailure_whenTheQueryFails() {
        when(outboxEventStore.countPending())
                .thenReturn(Mono.just(7L))
                .thenReturn(Mono.error(new TechnicalException(TechnicalErrorCode.DATABASE_ERROR)));
        OutboxBacklogGauge backlog = gauge(Duration.ofHours(1));
        StepVerifier.create(backlog.refreshOnce()).expectNext(7L).expectComplete().verify(Duration.ofSeconds(10));

        // A gauge dropping to zero during an outage would read as "all clear".
        StepVerifier.create(backlog.refreshOnce()).expectNext(7L).expectComplete().verify(Duration.ofSeconds(10));

        assertThat(pending()).isEqualTo(7.0);
        assertThat(technicalErrors("USR-TEC-002")).isEqualTo(1.0);
    }

    @Test
    void start_shouldRefreshOnThePeriod_andKeepGoingAfterAFailure() {
        when(outboxEventStore.countPending())
                .thenReturn(Mono.error(new TechnicalException(TechnicalErrorCode.DATABASE_ERROR)))
                .thenReturn(Mono.just(3L));
        OutboxBacklogGauge backlog = gauge(Duration.ofMillis(20));

        backlog.start();

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(pending()).isEqualTo(3.0));
        assertThat(backlog.isRunning()).isTrue();
        assertThat(technicalErrors("USR-TEC-002")).isEqualTo(1.0);
    }

    @Test
    void stop_shouldEndTheLoop() {
        when(outboxEventStore.countPending()).thenReturn(Mono.just(1L));
        OutboxBacklogGauge backlog = gauge(Duration.ofMillis(20));
        backlog.start();

        backlog.stop();

        assertThat(backlog.isRunning()).isFalse();
    }
}
