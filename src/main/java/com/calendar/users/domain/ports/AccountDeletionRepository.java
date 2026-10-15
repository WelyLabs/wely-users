package com.calendar.users.domain.ports;

import com.calendar.users.domain.models.AccountDeletionJob;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

/** The identity and media cleanup owed to deleted accounts, one job per account. */
public interface AccountDeletionRepository {

    /** Jobs whose next attempt is due, oldest first; a claimed job is not handed out again for a while. */
    Flux<AccountDeletionJob> claimDue(int limit);

    Mono<Void> markIdentityDeleted(UUID userId);

    Mono<Void> markMediaDeleted(UUID userId);

    /** Moves the next attempt without counting a failure. */
    Mono<Void> postpone(UUID userId, Instant nextAttemptAt);

    Mono<Void> recordFailure(UUID userId, String error, Instant nextAttemptAt);
}
