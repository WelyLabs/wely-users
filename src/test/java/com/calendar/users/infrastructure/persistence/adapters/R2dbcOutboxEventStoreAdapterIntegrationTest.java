package com.calendar.users.infrastructure.persistence.adapters;

import com.calendar.users.EphemeralDatabaseCheck;
import com.calendar.users.infrastructure.persistence.models.entities.OutboxEventEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Runs the outbox statements against a real PostgreSQL.
 *
 * <p>Every claim in {@link R2dbcOutboxEventStoreAdapter}'s documentation is a claim about
 * what the database does — {@code FOR UPDATE SKIP LOCKED} handing two callers disjoint
 * batches, {@code ORDER BY id} being publication order, an exhausted row dropping out of
 * the selection. None of that can be checked against a mock, and a relay that quietly
 * published everything twice would pass a unit test.
 *
 * <p>Opt-in like the other integration tests: CI sets {@code CI=true}, locally pass
 * {@code -Dintegration.tests=true}.
 */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
@EnabledIf("containersRequested")
class R2dbcOutboxEventStoreAdapterIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static boolean containersRequested() {
        return System.getenv("CI") != null || Boolean.getBoolean("integration.tests");
    }

    @Autowired
    private R2dbcOutboxEventStoreAdapter adapter;

    @Autowired
    private TransactionalOperator transactionalOperator;

    @Autowired
    private DatabaseClient databaseClient;

    private static boolean schemaApplied;

    @BeforeEach
    void prepareSchema() throws Exception {
        if (!schemaApplied) {
            // Read from db/migration rather than from a copy in the test resources: a copy
            // drifts, and the partial index and the attempts default are the subject here.
            databaseClient.sql(Files.readString(Path.of("db/migration/V2__create_outbox_event.sql")))
                    .then().block();
            schemaApplied = true;
        }

        // Before any DELETE. See EphemeralDatabaseCheck: this fixture empties its table,
        // and it has already been pointed at a deployed database once.
        EphemeralDatabaseCheck.verifyThrowaway(databaseClient);

        databaseClient.sql("DELETE FROM outbox_event").then().block();
    }

    private long append(String type, String payload) {
        return adapter.append(UUID.randomUUID(), type, payload).block();
    }

    private List<OutboxEventEntity> claim(int batchSize, int maxAttempts) {
        return transactionalOperator.transactional(
                adapter.claimPending(batchSize, maxAttempts).collectList()).block();
    }

    @Test
    @DisplayName("an appended event comes back with the id the sequence gave it")
    void append_shouldReturnTheGeneratedId() {
        UUID aggregateId = UUID.randomUUID();

        Long id = adapter.append(aggregateId, "USER_CREATED", "{\"userId\":\"x\"}").block();

        assertThat(id).isNotNull().isPositive();

        List<OutboxEventEntity> claimed = claim(10, 5);
        assertThat(claimed).singleElement().satisfies(row -> {
            assertThat(row.getId()).isEqualTo(id);
            assertThat(row.getAggregateId()).isEqualTo(aggregateId);
            assertThat(row.getType()).isEqualTo("USER_CREATED");
            assertThat(row.getPayload()).isEqualTo("{\"userId\":\"x\"}");
            assertThat(row.getAttempts()).isZero();
        });
    }

    @Test
    @DisplayName("events are claimed in id order, up to the batch size")
    void claimPending_shouldReadInOrderAndRespectTheBatchSize() {
        // Publication order is insertion order, and that is what makes per-aggregate
        // ordering on Kafka hold.
        long first = append("USER_CREATED", "{\"n\":1}");
        long second = append("USER_CREATED", "{\"n\":2}");
        append("USER_CREATED", "{\"n\":3}");

        assertThat(claim(2, 5)).extracting(OutboxEventEntity::getId).containsExactly(first, second);
    }

    @Test
    @DisplayName("two concurrent claims take disjoint batches instead of waiting on each other")
    void claimPending_shouldSkipRowsLockedByAnotherTransaction() {
        // The reason for SKIP LOCKED. Without it the second claim would either block until
        // the first commits, or — without FOR UPDATE at all — read the same rows and
        // publish every one of them twice.
        long one = append("USER_CREATED", "{\"n\":1}");
        long two = append("USER_CREATED", "{\"n\":2}");
        long three = append("USER_CREATED", "{\"n\":3}");
        long four = append("USER_CREATED", "{\"n\":4}");

        List<Long> held = new CopyOnWriteArrayList<>();
        Sinks.Empty<Void> keepOpen = Sinks.empty();

        // A transaction that claims two rows and then refuses to finish, so its locks stay.
        Disposable firstClaim = transactionalOperator.transactional(
                        adapter.claimPending(2, 5)
                                .doOnNext(row -> held.add(row.getId()))
                                .then(Mono.defer(keepOpen::asMono)))
                .subscribe();

        try {
            await().atMost(Duration.ofSeconds(10)).until(() -> held.size() == 2);
            assertThat(held).containsExactly(one, two);

            List<OutboxEventEntity> second = transactionalOperator.transactional(
                    adapter.claimPending(2, 5).collectList()).block(Duration.ofSeconds(10));

            assertThat(second).extracting(OutboxEventEntity::getId).containsExactly(three, four);
        } finally {
            keepOpen.tryEmitEmpty();
            firstClaim.dispose();
        }
    }

    @Test
    @DisplayName("a published event is not claimed again")
    void markPublished_shouldTakeEventsOutOfTheQueue() {
        long first = append("USER_CREATED", "{\"n\":1}");
        long second = append("USER_CREATED", "{\"n\":2}");

        assertThat(adapter.markPublished(List.of(first)).block()).isEqualTo(1L);

        assertThat(claim(10, 5)).extracting(OutboxEventEntity::getId).containsExactly(second);
    }

    @Test
    @DisplayName("marking nothing touches nothing")
    void markPublished_shouldAcceptAnEmptyBatch() {
        // The relay reaches this on every pass that claims an empty queue. Building
        // "id = ANY(array[])" instead would be a statement per idle second for no reason.
        append("USER_CREATED", "{\"n\":1}");

        assertThat(adapter.markPublished(List.of()).block()).isZero();
        assertThat(claim(10, 5)).hasSize(1);
    }

    @Test
    @DisplayName("an event that used up its attempts stops being claimed and stays in the table")
    void claimPending_shouldSkipExhaustedEvents() {
        // The poison message. Read in id order, it would otherwise be first on every pass
        // and nothing behind it would ever go out.
        long poison = append("USER_CREATED", "{\"broken\":true}");
        long behind = append("USER_CREATED", "{\"n\":2}");

        for (int attempt = 0; attempt < 3; attempt++) {
            adapter.recordFailure(poison, "could not be serialised").block();
        }

        assertThat(claim(10, 3)).extracting(OutboxEventEntity::getId).containsExactly(behind);

        Long stillThere = databaseClient
                .sql("SELECT attempts FROM outbox_event WHERE id = :id")
                .bind("id", poison)
                .map(row -> row.get("attempts", Integer.class).longValue())
                .one()
                .block();
        assertThat(stillThere).isEqualTo(3L);
    }

    @Test
    @DisplayName("a recorded failure counts an attempt and keeps the reason")
    void recordFailure_shouldIncrementAndStoreTheError() {
        long id = append("USER_CREATED", "{\"n\":1}");

        assertThat(adapter.recordFailure(id, "broker refused the message").block()).isEqualTo(1L);

        String lastError = databaseClient.sql("SELECT last_error FROM outbox_event WHERE id = :id")
                .bind("id", id)
                .map(row -> row.get("last_error", String.class))
                .one()
                .block();

        assertThat(lastError).isEqualTo("broker refused the message");
        assertThat(claim(10, 5)).singleElement()
                .satisfies(row -> assertThat(row.getAttempts()).isEqualTo(1));
    }

    @Test
    @DisplayName("a very long error is truncated rather than stored whole")
    void recordFailure_shouldTruncateTheStoredError() {
        // A driver stack trace rendered into a message runs to kilobytes, and the table
        // would end up carrying more failure text than payload.
        long id = append("USER_CREATED", "{\"n\":1}");

        adapter.recordFailure(id, "x".repeat(5000)).block();

        Integer stored = databaseClient.sql("SELECT length(last_error) FROM outbox_event WHERE id = :id")
                .bind("id", id)
                .map(row -> row.get(0, Integer.class))
                .one()
                .block();

        assertThat(stored).isEqualTo(1000);
    }

    @Test
    @DisplayName("the purge removes published events past the window and leaves the rest")
    void purgePublishedBefore_shouldOnlyDeleteOldPublishedEvents() {
        long old = append("USER_CREATED", "{\"n\":1}");
        long recent = append("USER_CREATED", "{\"n\":2}");
        long pending = append("USER_CREATED", "{\"n\":3}");

        adapter.markPublished(List.of(old, recent)).block();
        databaseClient.sql("UPDATE outbox_event SET published_at = now() - interval '30 days' WHERE id = :id")
                .bind("id", old)
                .then()
                .block();

        assertThat(adapter.purgePublishedBefore(Instant.now().minus(7, ChronoUnit.DAYS)).block())
                .isEqualTo(1L);

        List<Long> left = databaseClient.sql("SELECT id FROM outbox_event ORDER BY id")
                .map(row -> row.get("id", Long.class))
                .all()
                .collectList()
                .block();

        // An unpublished event is never purged, however old: it has not gone out yet.
        assertThat(left).containsExactly(recent, pending);
    }
}
