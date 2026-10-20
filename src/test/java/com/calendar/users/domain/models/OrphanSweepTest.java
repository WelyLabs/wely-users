package com.calendar.users.domain.models;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OrphanSweepTest {

    @Test
    void plus_shouldAddEachCountAndKeepEveryFailureCode() {
        assertThat(new OrphanSweep(1, 2, List.of("USR-TEC-001")).plus(new OrphanSweep(10, 20, List.of("USR-TEC-002"))))
                .isEqualTo(new OrphanSweep(11, 22, List.of("USR-TEC-001", "USR-TEC-002")));
    }

    @Test
    void empty_shouldBeTheNeutralElement() {
        OrphanSweep sweep = new OrphanSweep(1, 2, List.of("USR-TEC-001"));

        assertThat(OrphanSweep.EMPTY.plus(sweep)).isEqualTo(sweep);
    }

    @Test
    void failure_shouldCountOneFailedCheckUnderItsCode() {
        OrphanSweep failure = OrphanSweep.failure("USR-TEC-001");

        assertThat(failure.failed()).isEqualTo(1);
        assertThat(failure.failureCodes()).containsExactly("USR-TEC-001");
        assertThat(failure.confirmed()).isZero();
        assertThat(failure.removed()).isZero();
    }

    @Test
    void constructor_shouldCopyTheCodes_soTheSweepStaysImmutable() {
        List<String> codes = new ArrayList<>(List.of("USR-TEC-001"));
        OrphanSweep sweep = new OrphanSweep(0, 0, codes);

        codes.add("USR-TEC-002");

        assertThat(sweep.failed()).isEqualTo(1);
    }
}
