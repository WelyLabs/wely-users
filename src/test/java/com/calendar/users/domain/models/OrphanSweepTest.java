package com.calendar.users.domain.models;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OrphanSweepTest {

    @Test
    void plus_shouldAddEachCount() {
        assertThat(new OrphanSweep(1, 2, 3).plus(new OrphanSweep(10, 20, 30)))
                .isEqualTo(new OrphanSweep(11, 22, 33));
    }

    @Test
    void empty_shouldBeTheNeutralElement() {
        assertThat(OrphanSweep.EMPTY.plus(new OrphanSweep(1, 2, 3))).isEqualTo(new OrphanSweep(1, 2, 3));
    }
}
