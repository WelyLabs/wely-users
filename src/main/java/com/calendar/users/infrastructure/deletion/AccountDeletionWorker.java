package com.calendar.users.infrastructure.deletion;

import com.calendar.users.domain.models.DeletionProgress;
import com.calendar.users.domain.services.AccountDeletionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

import java.time.Duration;

/** Background loop that finishes deleted accounts (Keycloak logout and delete, avatar purge), retrying for ever. */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.account-deletion.worker", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class AccountDeletionWorker implements SmartLifecycle {

    private final AccountDeletionService accountDeletionService;
    private final Duration pollInterval;
    private final int batchSize;
    private final int alertAfterAttempts;

    private volatile Disposable loop;

    public AccountDeletionWorker(
            AccountDeletionService accountDeletionService,
            @Value("${app.account-deletion.worker.poll-interval:5s}") Duration pollInterval,
            @Value("${app.account-deletion.worker.batch-size:20}") int batchSize,
            @Value("${app.account-deletion.worker.alert-after-attempts:5}") int alertAfterAttempts) {
        this.accountDeletionService = accountDeletionService;
        this.pollInterval = pollInterval;
        this.batchSize = batchSize;
        this.alertAfterAttempts = alertAfterAttempts;
    }

    @Override
    public void start() {
        // Same shape as OrphanUserReaper: errors are caught inside the repeated Mono, or the loop stops.
        loop = Mono.defer(this::runOnce)
                .onErrorResume(error -> {
                    log.error("Account deletion pass failed; next attempt in {}", pollInterval, error);
                    return Mono.just(Pass.EMPTY);
                })
                .repeatWhen(passes -> passes.delayElements(pollInterval))
                .subscribe(
                        unused -> {
                        },
                        error -> log.error("Account deletion loop terminated and will not restart", error));
        log.info("Account deletion worker started: every {}, in batches of {}", pollInterval, batchSize);
    }

    @Override
    public void stop() {
        Disposable running = loop;
        loop = null;
        if (running != null) {
            running.dispose();
            log.info("Account deletion worker stopped");
        }
    }

    @Override
    public boolean isRunning() {
        return loop != null;
    }

    /** One pass over the due jobs; logs carry the businessId only. */
    Mono<Pass> runOnce() {
        return accountDeletionService.processDueDeletions(batchSize)
                .doOnNext(this::log)
                .reduce(Pass.EMPTY, Pass::plus);
    }

    private void log(DeletionProgress progress) {
        switch (progress.status()) {
            case COMPLETED -> log.info("Account deletion {} completed", progress.userId());
            case WAITING -> log.debug("Account deletion {} waiting for its next step", progress.userId());
            case FAILED -> {
                if (progress.attempts() >= alertAfterAttempts) {
                    log.error("Account deletion {} failed {} times ({}), retrying with backoff",
                            progress.userId(), progress.attempts(), progress.error());
                } else {
                    log.warn("Account deletion {} failed on attempt {} ({}), retrying with backoff",
                            progress.userId(), progress.attempts(), progress.error());
                }
            }
        }
    }

    /** Jobs per outcome in one pass. */
    record Pass(long completed, long waiting, long failed) {

        static final Pass EMPTY = new Pass(0, 0, 0);

        Pass plus(DeletionProgress progress) {
            return switch (progress.status()) {
                case COMPLETED -> new Pass(completed + 1, waiting, failed);
                case WAITING -> new Pass(completed, waiting + 1, failed);
                case FAILED -> new Pass(completed, waiting, failed + 1);
            };
        }
    }
}
