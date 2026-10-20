package com.calendar.users.infrastructure.deletion;

import com.calendar.users.domain.models.DeletionProgress;
import com.calendar.users.domain.services.AccountDeletionService;
import com.calendar.users.exception.GlobalErrorHandler;
import com.calendar.users.exception.TechnicalErrorCode;
import com.calendar.users.exception.TechnicalErrorCounter;
import com.calendar.users.exception.TechnicalException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Driven by real time at a short interval, like OutboxRelayTest: the loop's survival is what is tested. */
class AccountDeletionWorkerTest {

    private static final Duration FAST = Duration.ofMillis(20);

    private final AccountDeletionService service = mock(AccountDeletionService.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private AccountDeletionWorker worker;

    private AccountDeletionWorker worker(Duration pollInterval) {
        worker = new AccountDeletionWorker(service, new TechnicalErrorCounter(meterRegistry), pollInterval, 20, 5);
        return worker;
    }

    @AfterEach
    void stopWorker() {
        if (worker != null) {
            worker.stop();
        }
    }

    private double technicalErrors(String code) {
        Counter counter = meterRegistry.find(GlobalErrorHandler.TECHNICAL_ERRORS_METRIC)
                .tag(GlobalErrorHandler.CODE_TAG, code)
                .tag(GlobalErrorHandler.SOURCE_TAG, GlobalErrorHandler.SOURCE_JOB)
                .counter();
        return counter == null ? 0 : counter.count();
    }

    private static DeletionProgress progress(DeletionProgress.Status status, int attempts) {
        return new DeletionProgress(UUID.randomUUID(), status, attempts, status == DeletionProgress.Status.FAILED
                ? "USR-TEC-001" : null);
    }

    @Test
    void start_shouldProcessDueDeletions_everyPollInterval() {
        when(service.processDueDeletions(anyInt())).thenReturn(Flux.empty());

        worker(FAST).start();

        verify(service, timeout(2000).atLeast(3)).processDueDeletions(20);
        assertThat(worker.isRunning()).isTrue();
    }

    @Test
    void start_shouldKeepLooping_afterAFailedPass() {
        // An error reaching repeatWhen would stop the loop for good.
        when(service.processDueDeletions(anyInt()))
                .thenReturn(Flux.error(new IllegalStateException("database down")))
                .thenReturn(Flux.empty());

        worker(FAST).start();

        verify(service, timeout(2000).atLeast(2)).processDueDeletions(20);
        assertThat(worker.isRunning()).isTrue();
    }

    @Test
    void stop_shouldCancelTheLoop_whenCalled() {
        when(service.processDueDeletions(anyInt())).thenReturn(Flux.empty());
        AccountDeletionWorker running = worker(FAST);
        running.start();
        verify(service, timeout(2000).atLeast(1)).processDueDeletions(20);

        running.stop();
        clearInvocations(service);

        assertThat(running.isRunning()).isFalse();
        verify(service, after(200).never()).processDueDeletions(anyInt());
    }

    @Test
    void runOnce_shouldCountCompletedWaitingAndFailedJobs_whenAPassEnds() {
        when(service.processDueDeletions(20)).thenReturn(Flux.just(
                progress(DeletionProgress.Status.COMPLETED, 0),
                progress(DeletionProgress.Status.WAITING, 0),
                progress(DeletionProgress.Status.FAILED, 1),
                progress(DeletionProgress.Status.FAILED, 7)));

        StepVerifier.create(worker(Duration.ofHours(1)).runOnce())
                .expectNext(new AccountDeletionWorker.Pass(1, 1, 2))
                .expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void runOnce_shouldAnswerAnEmptyPass_whenNoJobIsDue() {
        when(service.processDueDeletions(20)).thenReturn(Flux.empty());

        StepVerifier.create(worker(Duration.ofHours(1)).runOnce())
                .expectNext(AccountDeletionWorker.Pass.EMPTY)
                .expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void stop_shouldDoNothing_whenNeverStarted() {
        AccountDeletionWorker idle = worker(FAST);

        idle.stop();

        assertThat(idle.isRunning()).isFalse();
        verify(service, never()).processDueDeletions(anyInt());
    }

    // --- technical error counter ------------------------------------------------

    @Test
    void runOnce_shouldCountEachFailedJob_asATechnicalFailureOfTheJob() {
        when(service.processDueDeletions(20)).thenReturn(Flux.just(
                progress(DeletionProgress.Status.COMPLETED, 0),
                progress(DeletionProgress.Status.FAILED, 1),
                progress(DeletionProgress.Status.FAILED, 7)));

        StepVerifier.create(worker(Duration.ofHours(1)).runOnce())
                .expectNextCount(1)
                .expectComplete().verify(Duration.ofSeconds(10));

        assertThat(technicalErrors("USR-TEC-001")).isEqualTo(2.0);
    }

    @Test
    void runOnce_shouldNotCount_whenAJobFailsOnABusinessRule() {
        when(service.processDueDeletions(20)).thenReturn(Flux.just(
                new DeletionProgress(UUID.randomUUID(), DeletionProgress.Status.FAILED, 1, "USR-BUS-001")));

        StepVerifier.create(worker(Duration.ofHours(1)).runOnce())
                .expectNextCount(1)
                .expectComplete().verify(Duration.ofSeconds(10));

        assertThat(meterRegistry.find(GlobalErrorHandler.TECHNICAL_ERRORS_METRIC).counters()).isEmpty();
    }

    @Test
    void start_shouldCountAFailedPass_underItsCode() {
        when(service.processDueDeletions(anyInt()))
                .thenReturn(Flux.error(new TechnicalException(TechnicalErrorCode.DATABASE_ERROR)))
                .thenReturn(Flux.empty());

        worker(FAST).start();

        verify(service, timeout(2000).atLeast(2)).processDueDeletions(20);
        assertThat(technicalErrors("USR-TEC-002")).isEqualTo(1.0);
    }
}
