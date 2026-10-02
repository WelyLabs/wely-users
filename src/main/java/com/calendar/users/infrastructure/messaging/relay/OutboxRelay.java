package com.calendar.users.infrastructure.messaging.relay;

import com.calendar.users.infrastructure.messaging.adapters.KafkaOutboxDispatcher;
import com.calendar.users.infrastructure.persistence.adapters.R2dbcOutboxEventStoreAdapter;
import com.calendar.users.infrastructure.persistence.models.entities.OutboxEventRow;
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
 * Carries committed outbox rows to Kafka.
 *
 * <p>The counterpart to {@code OutboxUserEventPublisherAdapter}: provisioning writes a row
 * and returns, and this moves it. It runs off the request path entirely, which is why a
 * broker outage no longer fails a signup — it only makes the backlog grow.
 *
 * <h2>The loop</h2>
 *
 * <pre>
 *   ① claim   SELECT … WHERE published_at IS NULL … FOR UPDATE SKIP LOCKED
 *   ② send    one message per row, in id order
 *   ③ mark    UPDATE published_at
 * </pre>
 *
 * <p>Steps ① to ③ are one transaction, so the locks taken by the claim are released by the
 * commit that records the outcome. The cost is that the transaction stays open across a
 * network call to the broker. The alternative — a short transaction that stamps the rows as
 * claimed, then the send, then a second transaction to mark them — keeps transactions brief
 * but adds a recovery case, because a row claimed by a process that then dies stays claimed
 * until something expires it. At one event per signup the first is the right trade; it is
 * written down because it is a choice, not an oversight.
 *
 * <h2>What this does not fix</h2>
 *
 * <p>The window between ② and ③. A process that dies there has sent the event and not
 * recorded it, so the row goes out again on the next pass. Delivery is at-least-once and
 * the consumer has to be idempotent — {@code wely-social} already is, since it creates its
 * user node with a Cypher {@code MERGE}.
 *
 * <h2>Why not {@code @Scheduled}</h2>
 *
 * <p>{@code @Scheduled} hands control to a blocking thread pool, from which a reactive
 * chain has to be subscribed by hand. {@code Mono.repeatWhen} keeps the loop in the same
 * model as the rest of the service and gives a property this service needs: the delay sits
 * between the end of one pass and the start of the next, so passes cannot overlap however
 * long a pass takes. {@code Flux.interval} would not — it emits on a timer regardless, and
 * fails outright with an overflow once the consumer falls behind.
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

    /**
     * Cancels both loops on shutdown.
     *
     * <p>A pass cancelled mid-flight rolls its transaction back, so the rows it had claimed
     * are simply unpublished again. If it was cancelled between the send and the mark, they
     * go out twice — the same at-least-once window as a crash, reached deliberately rather
     * than by accident.
     */
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
     * Repeats one pass forever, with {@code period} between the end of a pass and the start
     * of the next.
     *
     * <p>The {@code onErrorResume} is inside the repeated {@link Mono} on purpose. An error
     * that reaches {@code repeatWhen} cancels the repeat, and the loop then stops for good
     * with nothing to show for it but one line in the log — the failure mode that already
     * cost this project a chat sink and a Kafka consumer. Contained here, a failed pass is
     * logged and the next one still happens.
     *
     * <p>The error handler on {@code subscribe} is the second net, for anything that could
     * still terminate the sequence: without it such an error would be dropped silently.
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
                            // Each pass logs its own outcome; nothing to do per emission.
                        },
                        error -> log.error("Outbox {} loop terminated and will not restart", name, error));
    }

    /**
     * One claim-send-mark pass. Returns how many events were published.
     */
    private Mono<Long> drainOnce() {
        return transactionalOperator.transactional(
                        outboxEventStore.claimPending(batchSize, maxAttempts)
                                .concatMap(this::attemptDispatch)
                                .takeUntil(Attempt::failed)
                                .collectList()
                                .flatMap(this::markWhatWentOut))
                .flatMap(this::recordWhatDidNot);
    }

    /**
     * Turns a failed send into a value rather than an error signal.
     *
     * <p>An error here would abort the transaction and lose the marks for every event
     * already sent in this batch — which would then be sent again on the next pass. Keeping
     * the failure as data lets the successful prefix commit.
     */
    private Mono<Attempt> attemptDispatch(OutboxEventRow event) {
        return dispatcher.dispatch(event)
                .thenReturn(new Attempt(event, null))
                .onErrorResume(error -> Mono.just(new Attempt(event, error)));
    }

    /**
     * Marks the events that reached the broker. Still inside the transaction.
     *
     * <p>{@code takeUntil} upstream stops the batch at the first failure, so the list is a
     * run of successes with at most one failure at the end. Sending past a failure would
     * reorder events within a partition, which is the one ordering guarantee Kafka gives
     * and the one this design relies on.
     */
    private Mono<Pass> markWhatWentOut(List<Attempt> attempts) {
        List<Long> published = attempts.stream()
                .filter(attempt -> !attempt.failed())
                .map(attempt -> attempt.event().id())
                .toList();

        Attempt last = attempts.isEmpty() ? null : attempts.getLast();
        Pass pass = new Pass(published.size(), last != null && last.failed() ? last : null);

        return outboxEventStore.markPublished(published).thenReturn(pass);
    }

    /**
     * Counts the failed attempt, after the commit and in its own transaction.
     *
     * <p>Inside the claim transaction the increment would be rolled back together with
     * everything else when the pass aborts, and {@code attempts} would never leave zero —
     * the cap would never trigger and a poison event would block the queue forever.
     *
     * <p>Logged without the payload. An outbox row carries whatever the event carries, and
     * a relay that logs it on every failure copies user data into the cluster logs. The id,
     * the type and the error locate the problem; the row itself is one query away.
     */
    private Mono<Long> recordWhatDidNot(Pass pass) {
        if (pass.failure() == null) {
            if (pass.published() > 0) {
                log.info("Outbox relay published {} event(s)", pass.published());
            }
            return Mono.just(pass.published());
        }

        OutboxEventRow event = pass.failure().event();
        Throwable error = pass.failure().error();
        int attempt = event.attempts() + 1;

        if (attempt >= maxAttempts) {
            log.error("Outbox event {} ({}) failed {} times and will no longer be retried: {}",
                    event.id(), event.type(), attempt, error.toString());
        } else {
            log.warn("Outbox event {} ({}) failed on attempt {} of {}: {}",
                    event.id(), event.type(), attempt, maxAttempts, error.toString());
        }

        return outboxEventStore.recordFailure(event.id(), error.toString())
                .thenReturn(pass.published());
    }

    /**
     * Deletes published events past the retention window. Returns how many were removed.
     */
    private Mono<Long> purgeOnce() {
        return outboxEventStore.purgePublishedBefore(Instant.now().minus(retention))
                .doOnNext(removed -> {
                    if (removed > 0) {
                        log.info("Outbox purge removed {} published event(s) older than {}",
                                removed, retention);
                    }
                });
    }

    /** One event's delivery outcome. {@code error} is null when it reached the broker. */
    private record Attempt(OutboxEventRow event, Throwable error) {

        boolean failed() {
            return error != null;
        }
    }

    /** What one pass did: how many events went out, and the one that stopped it, if any. */
    private record Pass(long published, Attempt failure) {
    }
}
