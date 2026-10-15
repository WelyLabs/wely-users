package com.calendar.users.infrastructure.persistence.adapters;

import com.calendar.users.domain.models.AccountDeletionJob;
import com.calendar.users.domain.ports.AccountDeletionRepository;
import com.calendar.users.infrastructure.persistence.mappers.AccountDeletionEntityMapper;
import com.calendar.users.infrastructure.persistence.repositories.AccountDeletionR2dbcRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/** Persists deletion jobs through R2DBC; unexpected failures are mapped by {@code InfrastructureErrorAspect}. */
@Component
public class R2dbcAccountDeletionAdapter implements AccountDeletionRepository {

    private static final int MAX_STORED_ERROR_LENGTH = 1000;

    private final AccountDeletionR2dbcRepository repository;
    private final AccountDeletionEntityMapper mapper;
    private final Duration lease;

    public R2dbcAccountDeletionAdapter(AccountDeletionR2dbcRepository repository,
                                       AccountDeletionEntityMapper mapper,
                                       @Value("${app.account-deletion.worker.lease:5m}") Duration lease) {
        this.repository = repository;
        this.mapper = mapper;
        this.lease = lease;
    }

    @Override
    public Flux<AccountDeletionJob> claimDue(int limit) {
        return repository.claimDue(limit, lease.toSeconds()).map(mapper::toJob);
    }

    @Override
    public Mono<Void> markIdentityDeleted(UUID userId) {
        return repository.markIdentityDeleted(userId).then();
    }

    @Override
    public Mono<Void> markMediaDeleted(UUID userId) {
        return repository.markMediaDeleted(userId).then();
    }

    @Override
    public Mono<Void> postpone(UUID userId, Instant nextAttemptAt) {
        return repository.postpone(userId, utc(nextAttemptAt)).then();
    }

    @Override
    public Mono<Void> recordFailure(UUID userId, String error, Instant nextAttemptAt) {
        return repository.recordFailure(userId, truncate(error), utc(nextAttemptAt)).then();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static String truncate(String error) {
        if (error == null || error.length() <= MAX_STORED_ERROR_LENGTH) {
            return error;
        }
        return error.substring(0, MAX_STORED_ERROR_LENGTH);
    }
}
