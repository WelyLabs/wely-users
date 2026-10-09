package com.calendar.users.infrastructure.messaging.relay;

import com.calendar.users.infrastructure.messaging.adapters.KafkaOutboxDispatcher;
import com.calendar.users.infrastructure.persistence.adapters.R2dbcOutboxEventStoreAdapter;
import com.calendar.users.infrastructure.persistence.models.entities.OutboxEventEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;

/**
 * Background loop, started with the application, that sends pending outbox events to Kafka.
 *
 * <p>Each pass, in one transaction: claim pending rows, send them in order, mark them published.
 * A crash between the send and the mark sends the event again: delivery is at-least-once, so the
 * consumer must be idempotent (wely-social uses a Cypher MERGE).
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.outbox.relay", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class OutboxRelay implements SmartLifecycle {

    private final R2dbcOutboxEventStoreAdapter outboxEventStore;
    private final KafkaOutboxDispatcher dispatcher;
    private final TransactionalOperator transactionalOperator;

    private final Duration pollInterval;
    private final int batchSize;
    private final int maxAttempts;
    private final Duration purgeInterval;
    private final Duration retention;

    private volatile Disposable.Composite loops;

    public OutboxRelay(
            R2dbcOutboxEventStoreAdapter outboxEventStore,
            KafkaOutboxDispatcher dispatcher,
            TransactionalOperator transactionalOperator,
            @Value("${app.outbox.relay.poll-interval:2s}") Duration pollInterval,
            @Value("${app.outbox.relay.batch-size:100}") int batchSize,
            @Value("${app.outbox.relay.max-attempts:5}") int maxAttempts,
            @Value("${app.outbox.purge.interval:1h}") Duration purgeInterval,
            @Value("${app.outbox.purge.retention:7d}") Duration retention) {
        this.outboxEventStore = outboxEventStore;
        this.dispatcher = dispatcher;
        this.transactionalOperator = transactionalOperator;
        this.pollInterval = pollInterval;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
        this.purgeInterval = purgeInterval;
        this.retention = retention;
    }

    /** Called by Spring once the application has started. */
    @Override
    public void start() {
        Disposable.Composite started = Disposables.composite();
        started.add(loop("drain", pollInterval, this::drainOnce));
        started.add(loop("purge", purgeInterval, this::purgeOnce));
        loops = started;

        log.info("Outbox relay started: draining every {} in batches of {}, parking an event after "
                        + "{} failed attempts, purging published events older than {} every {}",
                pollInterval, batchSize, maxAttempts, retention, purgeInterval);
    }

    /** Called by Spring on shutdown. */
    @Override
    public void stop() {
        Disposable.Composite running = loops;
        loops = null;

        if (running != null) {
            running.dispose();
            log.info("Outbox relay stopped");
        }
    }

    @Override
    public boolean isRunning() {
        return loops != null;
    }

    /**
     * Repeats a pass forever, waiting {@code period} after each one, so passes never overlap.
     * Errors are caught inside the repeated Mono: one reaching {@code repeatWhen} would stop the loop.
     */
    private Disposable loop(String name, Duration period, Supplier<Mono<Long>> pass) {
        return Mono.defer(pass)
                .onErrorResume(error -> {
                    log.error("Outbox {} pass failed; next attempt in {}", name, period, error);
                    return Mono.just(0L);
                })
                .repeatWhen(passes -> passes.delayElements(period))
                .subscribe(
                        count -> {
                        },
                        error -> log.error("Outbox {} loop terminated and will not restart", name, error));
    }

    /** One pass: claim, send, mark. Returns how many events were published. */
    private Mono<Long> drainOnce() {
        return transactionalOperator.transactional(
                        outboxEventStore.claimPending(batchSize, maxAttempts)
                                .concatMap(this::attemptDispatch)
                                .takeUntil(Attempt::failed)
                                .collectList()
                                .flatMap(this::markWhatWentOut))
                .flatMap(this::recordWhatDidNot);
    }

    /** Keeps a failed send as a value, so the events sent before it can still be marked. */
    private Mono<Attempt> attemptDispatch(OutboxEventEntity event) {
        return dispatcher.dispatch(event)
                .thenReturn(new Attempt(event, null))
                .onErrorResume(error -> Mono.just(new Attempt(event, error)));
    }

    /** Marks the events that reached Kafka. The batch stopped at the first failure, to keep order. */
    private Mono<Pass> markWhatWentOut(List<Attempt> attempts) {
        List<Long> published = attempts.stream()
                .filter(attempt -> !attempt.failed())
                .map(attempt -> attempt.event().getId())
                .toList();

        Attempt last = attempts.isEmpty() ? null : attempts.getLast();
        Pass pass = new Pass(published.size(), last != null && last.failed() ? last : null);

        return outboxEventStore.markPublished(published).thenReturn(pass);
    }

    /**
     * Counts the failed attempt outside the pass transaction, which would otherwise roll it back.
     * The payload is not logged: it holds user data.
     */
    private Mono<Long> recordWhatDidNot(Pass pass) {
        if (pass.failure() == null) {
            if (pass.published() > 0) {
                log.info("Outbox relay published {} event(s)", pass.published());
            }
            return Mono.just(pass.published());
        }

        OutboxEventEntity event = pass.failure().event();
        Throwable error = pass.failure().error();
        int attempt = event.getAttempts() + 1;

        if (attempt >= maxAttempts) {
            log.error("Outbox event {} ({}) failed {} times and will no longer be retried: {}",
                    event.getId(), event.getType(), attempt, error.toString());
        } else {
            log.warn("Outbox event {} ({}) failed on attempt {} of {}: {}",
                    event.getId(), event.getType(), attempt, maxAttempts, error.toString());
        }

        return outboxEventStore.recordFailure(event.getId(), error.toString())
                .thenReturn(pass.published());
    }

    /** Deletes published events older than the retention window. */
    private Mono<Long> purgeOnce() {
        return outboxEventStore.purgePublishedBefore(Instant.now().minus(retention))
                .doOnNext(removed -> {
                    if (removed > 0) {
                        log.info("Outbox purge removed {} published event(s) older than {}",
                                removed, retention);
                    }
                });
    }

    /** One event's delivery outcome; {@code error} is null on success. */
    private record Attempt(OutboxEventEntity event, Throwable error) {

        boolean failed() {
            return error != null;
        }
    }

    /** What a pass did: events sent, and the failure that stopped it, if any. */
    private record Pass(long published, Attempt failure) {
    }
}
