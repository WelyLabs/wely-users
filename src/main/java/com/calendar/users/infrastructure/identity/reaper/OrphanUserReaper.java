package com.calendar.users.infrastructure.identity.reaper;

import com.calendar.users.domain.services.UserService;
import com.calendar.users.exception.GlobalErrorHandler;
import com.calendar.users.exception.TechnicalErrorCounter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Background loop, started with the application, that deletes users whose Keycloak account does
 * not exist: the leftovers of a registration Keycloak cancelled after wely-users created the user.
 *
 * <p>Only users older than the grace period are checked, so a registration still in flight is
 * never mistaken for one. Each user is checked once.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.orphan-reaper", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class OrphanUserReaper implements SmartLifecycle {

    private final UserService userService;
    private final TechnicalErrorCounter technicalErrors;
    private final Duration interval;
    private final Duration gracePeriod;
    private final int batchSize;

    private volatile Disposable loop;

    public OrphanUserReaper(
            UserService userService,
            TechnicalErrorCounter technicalErrors,
            @Value("${app.orphan-reaper.interval:1h}") Duration interval,
            @Value("${app.orphan-reaper.grace-period:15m}") Duration gracePeriod,
            @Value("${app.orphan-reaper.batch-size:100}") int batchSize) {
        this.userService = userService;
        this.technicalErrors = technicalErrors;
        this.interval = interval;
        this.gracePeriod = gracePeriod;
        this.batchSize = batchSize;
    }

    @Override
    public void start() {
        // Same shape as OutboxRelay: errors are caught inside the repeated Mono, or the loop stops.
        loop = Mono.defer(this::sweepOnce)
                .onErrorResume(error -> {
                    log.error("Orphan sweep failed; next attempt in {}", interval, error);
                    technicalErrors.count(error, GlobalErrorHandler.SOURCE_JOB);
                    return Mono.empty();
                })
                .repeatWhen(passes -> passes.delayElements(interval))
                .subscribe(
                        unused -> {
                        },
                        error -> {
                            log.error("Orphan reaper loop terminated and will not restart", error);
                            technicalErrors.count(error, GlobalErrorHandler.SOURCE_JOB);
                        });
        log.info("Orphan reaper started: every {}, users older than {}, in batches of {}",
                interval, gracePeriod, batchSize);
    }

    @Override
    public void stop() {
        Disposable running = loop;
        loop = null;
        if (running != null) {
            running.dispose();
            log.info("Orphan reaper stopped");
        }
    }

    @Override
    public boolean isRunning() {
        return loop != null;
    }

    Mono<Void> sweepOnce() {
        LocalDateTime createdBefore = LocalDateTime.now().minus(gracePeriod);
        return userService.removeOrphans(createdBefore, batchSize)
                .doOnNext(sweep -> {
                    // Keycloak or the database down for a user: left for the next pass, counted now.
                    sweep.failureCodes().forEach(code -> technicalErrors.count(code, GlobalErrorHandler.SOURCE_JOB));
                    if (sweep.removed() > 0 || sweep.failed() > 0) {
                        log.info("Orphan sweep: {} confirmed, {} removed, {} left for the next pass",
                                sweep.confirmed(), sweep.removed(), sweep.failed());
                    }
                })
                .then();
    }
}
