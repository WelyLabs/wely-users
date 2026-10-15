package com.calendar.users.infrastructure.persistence.adapters;

import com.calendar.users.EphemeralDatabaseCheck;
import com.calendar.users.FlywayTestDatabase;
import com.calendar.users.domain.models.AccountDeletionJob;
import com.calendar.users.domain.models.BusinessUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.awaitility.Awaitility.await;

/** The deletion job statements against a real PostgreSQL; opt-in with CI=true or -Dintegration.tests=true. */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
@EnabledIf("containersRequested")
class R2dbcAccountDeletionAdapterIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static boolean containersRequested() {
        return System.getenv("CI") != null || Boolean.getBoolean("integration.tests");
    }

    @DynamicPropertySource
    static void migrations(DynamicPropertyRegistry registry) {
        FlywayTestDatabase.migrate(registry, POSTGRES);
    }

    @Autowired
    private R2dbcAccountDeletionAdapter adapter;

    @Autowired
    private R2dbcUserRepositoryAdapter users;

    @Autowired
    private TransactionalOperator transactionalOperator;

    @Autowired
    private DatabaseClient databaseClient;

    @BeforeEach
    void prepareSchema() {
        // Before any DELETE: see EphemeralDatabaseCheck.
        EphemeralDatabaseCheck.verifyThrowaway(databaseClient);

        databaseClient.sql("DELETE FROM account_deletion").then().block();
        databaseClient.sql("DELETE FROM retired_handle").then().block();
        databaseClient.sql("DELETE FROM app_user").then().block();
        databaseClient.sql("DELETE FROM outbox_event").then().block();
    }

    /** A user with a job requested this many minutes ago, due as long as nextAttemptInMinutes is not positive. */
    private UUID aJob(int requestedMinutesAgo, int nextAttemptInMinutes) {
        BusinessUser user = users.save(new BusinessUser(null, "user" + UUID.randomUUID().toString().substring(0, 8),
                1111, null, null, null, LocalDateTime.now()), "kc-" + UUID.randomUUID()).block();
        databaseClient.sql("""
                        INSERT INTO account_deletion (user_id, keycloak_id, requested_at, next_attempt_at)
                        VALUES (:id, :keycloakId, now() - make_interval(mins => :ago), now() + make_interval(mins => :next))
                        """)
                .bind("id", user.id())
                .bind("keycloakId", "kc-" + user.id())
                .bind("ago", requestedMinutesAgo)
                .bind("next", nextAttemptInMinutes)
                .then()
                .block();
        return user.id();
    }

    private Map<String, Object> jobOf(UUID userId) {
        return databaseClient.sql("SELECT * FROM account_deletion WHERE user_id = :id")
                .bind("id", userId)
                .fetch()
                .one()
                .block();
    }

    private static Instant instant(Object column) {
        return ((OffsetDateTime) column).toInstant();
    }

    @Test
    @DisplayName("due unfinished jobs are claimed oldest first; a job not due yet is left")
    void claimDue_shouldReturnDueUnfinishedJobsOldestFirst_whenSeveralAreDue() {
        UUID newer = aJob(1, 0);
        UUID oldest = aJob(3, -1);
        UUID middle = aJob(2, 0);
        aJob(4, 10);

        List<AccountDeletionJob> claimed = adapter.claimDue(10).collectList().block();

        assertThat(claimed).extracting(AccountDeletionJob::userId).containsExactly(oldest, middle, newer);
        assertThat(claimed.getFirst()).satisfies(job -> {
            assertThat(job.subjectId()).isEqualTo("kc-" + oldest);
            assertThat(job.identityDeleted()).isFalse();
            assertThat(job.mediaDeleted()).isFalse();
            assertThat(job.attempts()).isZero();
        });
    }

    @Test
    @DisplayName("the limit bounds one claim")
    void claimDue_shouldClaimAtMostTheLimit_whenMoreAreDue() {
        aJob(2, 0);
        aJob(1, 0);

        assertThat(adapter.claimDue(1).collectList().block()).hasSize(1);
    }

    @Test
    @DisplayName("a claimed job is leased: the next claim does not hand it out again")
    void claimDue_shouldNotReturnAJobTwice_withinTheLease() {
        UUID job = aJob(1, 0);

        assertThat(adapter.claimDue(10).collectList().block()).hasSize(1);
        assertThat(adapter.claimDue(10).collectList().block()).isEmpty();

        assertThat(instant(jobOf(job).get("next_attempt_at")))
                .isCloseTo(Instant.now().plus(Duration.ofMinutes(5)), within(1, ChronoUnit.MINUTES));
    }

    @Test
    @DisplayName("two workers claiming at once take different jobs instead of waiting on each other")
    void claimDue_shouldSkipRowsLockedByAnotherTransaction_whenTwoWorkersClaim() {
        UUID locked = aJob(2, 0);
        UUID free = aJob(1, 0);
        AtomicBoolean held = new AtomicBoolean();
        Sinks.Empty<Void> keepOpen = Sinks.empty();

        Disposable firstWorker = transactionalOperator.transactional(
                        databaseClient.sql("SELECT user_id FROM account_deletion WHERE user_id = :id FOR UPDATE")
                                .bind("id", locked)
                                .fetch()
                                .one()
                                .doOnNext(row -> held.set(true))
                                .then(Mono.defer(keepOpen::asMono)))
                .subscribe();

        try {
            await().atMost(Duration.ofSeconds(10)).untilTrue(held);

            List<AccountDeletionJob> claimed = adapter.claimDue(10).collectList().block(Duration.ofSeconds(10));

            assertThat(claimed).extracting(AccountDeletionJob::userId).containsExactly(free);
        } finally {
            keepOpen.tryEmitEmpty();
            firstWorker.dispose();
        }
    }

    @Test
    @DisplayName("a job whose two steps are done is never claimed again")
    void claimDue_shouldSkipFinishedJobs_whenBothStepsAreDone() {
        UUID done = aJob(20, -5);
        adapter.markIdentityDeleted(done).block();
        adapter.markMediaDeleted(done).block();
        UUID halfDone = aJob(20, -5);
        adapter.markIdentityDeleted(halfDone).block();

        assertThat(adapter.claimDue(10).collectList().block())
                .extracting(AccountDeletionJob::userId)
                .containsExactly(halfDone);
    }

    @Test
    @DisplayName("once Keycloak confirmed, the job keeps when, and no longer the Keycloak id")
    void markIdentityDeleted_shouldSetTheTimeAndNullTheKeycloakId_whenCalled() {
        UUID job = aJob(1, 0);

        adapter.markIdentityDeleted(job).block();

        Map<String, Object> row = jobOf(job);
        assertThat(row.get("keycloak_id")).isNull();
        assertThat(row.get("identity_deleted_at")).isNotNull();
        assertThat(row.get("media_deleted_at")).isNull();
    }

    @Test
    void markMediaDeleted_shouldSetTheTime_whenCalled() {
        UUID job = aJob(1, 0);

        adapter.markMediaDeleted(job).block();

        Map<String, Object> row = jobOf(job);
        assertThat(row.get("media_deleted_at")).isNotNull();
        assertThat(row.get("keycloak_id")).isEqualTo("kc-" + job);
    }

    @Test
    void postpone_shouldMoveTheNextAttemptWithoutCountingAFailure_whenCalled() {
        UUID job = aJob(1, 0);
        Instant later = Instant.now().plus(Duration.ofMinutes(9)).truncatedTo(ChronoUnit.MICROS);

        adapter.postpone(job, later).block();

        Map<String, Object> row = jobOf(job);
        assertThat(instant(row.get("next_attempt_at"))).isEqualTo(later);
        assertThat(row.get("attempts")).isEqualTo(0);
        assertThat(adapter.claimDue(10).collectList().block()).isEmpty();
    }

    @Test
    void recordFailure_shouldIncrementAttemptsStoreTheErrorAndScheduleTheRetry_whenCalled() {
        UUID job = aJob(1, 0);
        Instant retry = Instant.now().plusSeconds(5).truncatedTo(ChronoUnit.MICROS);

        adapter.recordFailure(job, "USR-TEC-001", retry).block();
        adapter.recordFailure(job, "USR-TEC-004", retry).block();

        Map<String, Object> row = jobOf(job);
        assertThat(row.get("attempts")).isEqualTo(2);
        assertThat(row.get("last_error")).isEqualTo("USR-TEC-004");
        assertThat(instant(row.get("next_attempt_at"))).isEqualTo(retry);
    }
}
