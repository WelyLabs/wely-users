package com.calendar.users.domain.models;

/** Outcome of one pass over unchecked users. */
public record OrphanSweep(long confirmed, long removed, long failed) {

    public static final OrphanSweep EMPTY = new OrphanSweep(0, 0, 0);

    public OrphanSweep plus(OrphanSweep other) {
        return new OrphanSweep(confirmed + other.confirmed, removed + other.removed, failed + other.failed);
    }
}
