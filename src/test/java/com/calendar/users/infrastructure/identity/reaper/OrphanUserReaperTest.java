package com.calendar.users.infrastructure.identity.reaper;

import com.calendar.users.domain.models.OrphanSweep;
import com.calendar.users.domain.services.UserService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.LocalDateTime;

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

    private OrphanUserReaper reaper(Duration interval) {
        return new OrphanUserReaper(userService, interval, Duration.ofMinutes(15), 50);
    }

    @Test
    void sweepOnce_shouldOnlyCheckUsersOlderThanTheGracePeriod() {
        when(userService.removeOrphans(any(), anyInt())).thenReturn(Mono.just(new OrphanSweep(1, 1, 0)));
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
}
