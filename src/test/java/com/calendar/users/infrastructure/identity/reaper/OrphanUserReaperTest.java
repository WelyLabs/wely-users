package com.calendar.users.infrastructure.identity.reaper;

import com.calendar.users.domain.models.OrphanSweep;
import com.calendar.users.domain.services.UserService;
import com.calendar.users.exception.GlobalErrorHandler;
import com.calendar.users.exception.TechnicalErrorCode;
import com.calendar.users.exception.TechnicalErrorCounter;
import com.calendar.users.exception.TechnicalException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrphanUserReaperTest {

    private final UserService userService = mock(UserService.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    private OrphanUserReaper reaper(Duration interval) {
        return new OrphanUserReaper(userService, new TechnicalErrorCounter(meterRegistry), interval,
                Duration.ofMinutes(15), 50);
    }

    private double technicalErrors(String code) {
        Counter counter = meterRegistry.find(GlobalErrorHandler.TECHNICAL_ERRORS_METRIC)
                .tag(GlobalErrorHandler.CODE_TAG, code)
                .tag(GlobalErrorHandler.SOURCE_TAG, GlobalErrorHandler.SOURCE_JOB)
                .counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void sweepOnce_shouldOnlyCheckUsersOlderThanTheGracePeriod() {
        when(userService.removeOrphans(any(), anyInt())).thenReturn(Mono.just(new OrphanSweep(1, 1, List.of())));
        LocalDateTime before = LocalDateTime.now().minusMinutes(15);

        StepVerifier.create(reaper(Duration.ofHours(1)).sweepOnce()).expectComplete().verify(Duration.ofSeconds(10));

        LocalDateTime after = LocalDateTime.now().minusMinutes(15);
        ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.captor();
        verify(userService).removeOrphans(cutoff.capture(), eq(50));
        assertThat(cutoff.getValue()).isBetween(before, after);
    }

    @Test
    void start_shouldKeepSweeping_afterAFailedPass() {
        // An error reaching repeatWhen would stop the loop for good.
        when(userService.removeOrphans(any(), anyInt()))
                .thenReturn(Mono.error(new IllegalStateException("database down")))
                .thenReturn(Mono.just(OrphanSweep.EMPTY));
        OrphanUserReaper reaper = reaper(Duration.ofMillis(20));

        reaper.start();
        try {
            verify(userService, timeout(2000).atLeast(2)).removeOrphans(any(), anyInt());
            assertThat(reaper.isRunning()).isTrue();
        } finally {
            reaper.stop();
        }
        assertThat(reaper.isRunning()).isFalse();
    }

    @Test
    @DisplayName("each user left unchecked by an outage is counted under the outage's code")
    void sweepOnce_shouldCountEachFailedCheck_asATechnicalFailureOfTheJob() {
        when(userService.removeOrphans(any(), anyInt()))
                .thenReturn(Mono.just(new OrphanSweep(1, 0, List.of("USR-TEC-001", "USR-TEC-001"))));

        StepVerifier.create(reaper(Duration.ofHours(1)).sweepOnce()).expectComplete().verify(Duration.ofSeconds(10));

        assertThat(technicalErrors("USR-TEC-001")).isEqualTo(2.0);
    }

    @Test
    void sweepOnce_shouldCountNothing_whenEveryCheckCompletes() {
        when(userService.removeOrphans(any(), anyInt())).thenReturn(Mono.just(new OrphanSweep(3, 1, List.of())));

        StepVerifier.create(reaper(Duration.ofHours(1)).sweepOnce()).expectComplete().verify(Duration.ofSeconds(10));

        assertThat(meterRegistry.find(GlobalErrorHandler.TECHNICAL_ERRORS_METRIC).counters()).isEmpty();
    }

    @Test
    void start_shouldCountAFailedSweep_underItsCode() {
        when(userService.removeOrphans(any(), anyInt()))
                .thenReturn(Mono.error(new TechnicalException(TechnicalErrorCode.DATABASE_ERROR)))
                .thenReturn(Mono.just(OrphanSweep.EMPTY));
        OrphanUserReaper reaper = reaper(Duration.ofMillis(20));

        reaper.start();
        try {
            verify(userService, timeout(2000).atLeast(2)).removeOrphans(any(), anyInt());
            assertThat(technicalErrors("USR-TEC-002")).isEqualTo(1.0);
        } finally {
            reaper.stop();
        }
    }
}
