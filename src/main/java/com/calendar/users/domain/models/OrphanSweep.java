package com.calendar.users.domain.models;

import java.util.List;
import java.util.stream.Stream;

/**
 * Outcome of one pass over unchecked users.
 *
 * <p>A failed check keeps its error code rather than a bare count: the reaper, outside the
 * domain, counts each one as a technical failure of the job.
 */
public record OrphanSweep(long confirmed, long removed, List<String> failureCodes) {

    public static final OrphanSweep EMPTY = new OrphanSweep(0, 0, List.of());

    public OrphanSweep {
        failureCodes = List.copyOf(failureCodes);
    }

    /** One check that could not complete, for the reason the code names. */
    public static OrphanSweep failure(String errorCode) {
        return new OrphanSweep(0, 0, List.of(errorCode));
    }

    public long failed() {
        return failureCodes.size();
    }

    public OrphanSweep plus(OrphanSweep other) {
        return new OrphanSweep(confirmed + other.confirmed, removed + other.removed,
                Stream.concat(failureCodes.stream(), other.failureCodes.stream()).toList());
    }
}
