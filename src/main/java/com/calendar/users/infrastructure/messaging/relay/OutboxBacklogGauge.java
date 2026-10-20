package com.calendar.users.infrastructure.messaging.relay;

import com.calendar.users.exception.GlobalErrorHandler;
import com.calendar.users.exception.TechnicalErrorCounter;
import com.calendar.users.infrastructure.persistence.adapters.R2dbcOutboxEventStoreAdapter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Publishes {@code wely.outbox.pending}: how many outbox events are not sent yet.
 *
 * <p>A gauge is read on every scrape, on the scraper's thread: it must not query. The count is
 * refreshed on a period by a reactive query into an {@link AtomicLong}, which the gauge reads. A
 * failed refresh keeps the last value — a gauge dropping to zero during a database outage would
 * read as "all clear" — and is counted as a technical failure of a job.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.outbox.backlog", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OutboxBacklogGauge implements SmartLifecycle {

    static final String PENDING = "wely.outbox.pending";
    static final String OUTBOX_TAG = "outbox";
    /** This service has one outbox: the table's name. */
    static final String OUTBOX_NAME = "outbox_event";

    private final R2dbcOutboxEventStoreAdapter outboxEventStore;
    private final TechnicalErrorCounter technicalErrors;
    private final Duration refreshInterval;
    private final AtomicLong pending = new AtomicLong();

    private volatile Disposable loop;

    public OutboxBacklogGauge(R2dbcOutboxEventStoreAdapter outboxEventStore,
                              TechnicalErrorCounter technicalErrors,
                              MeterRegistry meterRegistry,
                              @Value("${app.outbox.backlog.refresh-interval:30s}") Duration refreshInterval) {
        this.outboxEventStore = outboxEventStore;
        this.technicalErrors = technicalErrors;
        this.refreshInterval = refreshInterval;
        Gauge.builder(PENDING, pending, AtomicLong::get)
                .description("Outbox events not published yet")
                .tag(OUTBOX_TAG, OUTBOX_NAME)
                .register(meterRegistry);
    }

    @Override
    public void start() {
        // concatMap: a slow count is never overlapped by the next one.
        loop = Flux.interval(Duration.ZERO, refreshInterval)
                .concatMap(tick -> refreshOnce())
                .subscribe(
                        count -> {
                        },
                        error -> {
                            log.error("Outbox backlog loop terminated and will not restart", error);
                            technicalErrors.count(error, GlobalErrorHandler.SOURCE_JOB);
                        });
    }

    @Override
    public void stop() {
        Disposable running = loop;
        loop = null;
        if (running != null) {
            running.dispose();
        }
    }

    @Override
    public boolean isRunning() {
        return loop != null;
    }

    /** One refresh: the new count, or the last one kept when the query fails. */
    Mono<Long> refreshOnce() {
        return outboxEventStore.countPending()
                .doOnNext(pending::set)
                .onErrorResume(error -> {
                    log.warn("Outbox backlog refresh failed; keeping {}: {}", pending.get(), error.toString());
                    technicalErrors.count(error, GlobalErrorHandler.SOURCE_JOB);
                    return Mono.just(pending.get());
                });
    }
}
