package com.calendar.users.infrastructure.persistence.repositories;

import com.calendar.users.infrastructure.persistence.models.entities.AccountDeletionEntity;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Spring Data access to {@code account_deletion}. */
@Repository
public interface AccountDeletionR2dbcRepository extends ReactiveCrudRepository<AccountDeletionEntity, UUID> {

    @Modifying
    @Query("""
            INSERT INTO account_deletion (user_id, subject_id, requested_at, next_attempt_at)
            VALUES (:userId, :subjectId, :requestedAt, :dueAt)
            ON CONFLICT (user_id) DO NOTHING
            """)
    // next_attempt_at is the end of the grace period: claimDue leaves the job alone until then.
    Mono<Long> insertJob(UUID userId, String subjectId, OffsetDateTime requestedAt, OffsetDateTime dueAt);

    /** Leases the due unfinished jobs, so a second worker skips them without a transaction held across HTTP calls. */
    @Query("""
            WITH due AS (
                SELECT user_id
                  FROM account_deletion
                 WHERE (identity_deleted_at IS NULL OR media_deleted_at IS NULL)
                   AND next_attempt_at <= now()
                 ORDER BY next_attempt_at, user_id
                 LIMIT :limit
                FOR UPDATE SKIP LOCKED
            ), claimed AS (
                UPDATE account_deletion job
                   SET next_attempt_at = now() + make_interval(secs => :leaseSeconds)
                  FROM due
                 WHERE job.user_id = due.user_id
                RETURNING job.*
            )
            SELECT * FROM claimed ORDER BY requested_at, user_id
            """)
    // Not @Modifying, so the returned rows are mapped.
    Flux<AccountDeletionEntity> claimDue(int limit, long leaseSeconds);

    @Modifying
    @Query("UPDATE account_deletion SET identity_deleted_at = now(), subject_id = NULL WHERE user_id = :userId")
    Mono<Long> markIdentityDeleted(UUID userId);

    @Modifying
    @Query("UPDATE account_deletion SET media_deleted_at = now() WHERE user_id = :userId")
    Mono<Long> markMediaDeleted(UUID userId);

    @Modifying
    @Query("UPDATE account_deletion SET next_attempt_at = :nextAttemptAt WHERE user_id = :userId")
    Mono<Long> postpone(UUID userId, OffsetDateTime nextAttemptAt);

    // The database increments, so two concurrent failures cannot overwrite each other's count.
    @Modifying
    @Query("""
            UPDATE account_deletion
               SET attempts = attempts + 1, last_error = :error, next_attempt_at = :nextAttemptAt
             WHERE user_id = :userId
            """)
    Mono<Long> recordFailure(UUID userId, String error, OffsetDateTime nextAttemptAt);
}
