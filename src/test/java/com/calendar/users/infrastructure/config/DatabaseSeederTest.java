package com.calendar.users.infrastructure.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.FetchSpec;
import org.springframework.r2dbc.core.RowsFetchSpec;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers {@link DatabaseSeeder}: the decision to seed or skip, and the statement it builds.
 *
 * <p>The seeder only runs under the {@code local} profile, so none of this executes in
 * production. It is tested all the same because the SQL is assembled by string concatenation,
 * which is the one shape of code where a mistake yields a statement that is merely wrong rather
 * than one that fails to compile — and because the previous version bound no value for two
 * NOT NULL columns, so the insert failed after the run had already started.
 *
 * <p>{@code run} is fire-and-forget on {@code boundedElastic}, so each case waits on a latch
 * released by the mock rather than sleeping.
 */
class DatabaseSeederTest {

    private static final int USER_COUNT = 1_000_000;
    private static final long TIMEOUT_SECONDS = 5;

    private R2dbcEntityTemplate template;
    private DatabaseClient databaseClient;
    private DatabaseClient.GenericExecuteSpec executeSpec;
    private DatabaseSeeder seeder;

    /** Every statement the seeder asked for, in order. */
    private final List<String> statements = new CopyOnWriteArrayList<>();

    /** Released once the seeder has finished with the database, however that went. */
    private CountDownLatch done;

    @BeforeEach
    void setUp() {
        template = mock(R2dbcEntityTemplate.class);
        databaseClient = mock(DatabaseClient.class);
        executeSpec = mock(DatabaseClient.GenericExecuteSpec.class, Answers.RETURNS_SELF);
        done = new CountDownLatch(1);

        when(template.getDatabaseClient()).thenReturn(databaseClient);
        when(databaseClient.sql(anyString())).thenAnswer(invocation -> {
            statements.add(invocation.getArgument(0));
            return executeSpec;
        });

        seeder = new DatabaseSeeder(template);
    }

    /** Stubs the row count, and releases the latch when the seeder decides to stop there. */
    @SuppressWarnings("unchecked")
    private void stubExistingCount(long existing) {
        RowsFetchSpec<Long> rows = mock(RowsFetchSpec.class);
        when(executeSpec.map(any(BiFunction.class))).thenReturn((RowsFetchSpec) rows);
        when(rows.first()).thenReturn(Mono.just(existing).doFinally(signal -> {
            if (existing >= USER_COUNT) {
                done.countDown();
            }
        }));
    }

    /** Stubs the insert, releasing the latch on the first batch. */
    @SuppressWarnings("unchecked")
    private void stubInsertSucceeds(long rowsPerBatch) {
        FetchSpec<Object> fetchSpec = mock(FetchSpec.class);
        when(executeSpec.fetch()).thenReturn((FetchSpec) fetchSpec);
        when(fetchSpec.rowsUpdated()).thenAnswer(invocation -> {
            done.countDown();
            return Mono.just(rowsPerBatch);
        });
    }

    private void runAndWait() throws InterruptedException {
        seeder.run(new DefaultApplicationArguments());
        assertThat(done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .as("the seeder finished with the database")
                .isTrue();
    }

    private String firstInsert() {
        return statements.stream()
                .filter(statement -> statement.startsWith("INSERT"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no INSERT was issued"));
    }

    @Test
    @DisplayName("counts what is already there before inserting anything")
    void run_shouldCountExistingUsersFirst() throws InterruptedException {
        stubExistingCount(USER_COUNT);

        runAndWait();

        assertThat(statements).first().isEqualTo("SELECT COUNT(*) FROM app_user");
    }

    @Test
    @DisplayName("inserts nothing when the table already holds the target count")
    void run_shouldSkipWhenAlreadySeeded() throws InterruptedException {
        // Restarting the application locally must not append another million rows.
        stubExistingCount(USER_COUNT);

        runAndWait();

        assertThat(statements).noneMatch(statement -> statement.startsWith("INSERT"));
    }

    @Test
    @DisplayName("inserts when the table is empty")
    void run_shouldSeedWhenTheTableIsEmpty() throws InterruptedException {
        stubExistingCount(0L);
        stubInsertSucceeds(5_000L);

        runAndWait();

        assertThat(firstInsert()).startsWith("INSERT INTO app_user");
    }

    @Test
    @DisplayName("every NOT NULL column appears in the statement and is bound")
    void run_shouldBindEveryNotNullColumn() throws InterruptedException {
        stubExistingCount(USER_COUNT - 1);
        stubInsertSucceeds(1L);

        runAndWait();

        assertThat(firstInsert())
                .contains("keycloak_id", "user_name", "hashtag", "profile_pic_url", "joined_date")
                .contains("(:k0, :u0, :h0, :p0, :j0)");

        verify(executeSpec, atLeastOnce()).bind(eq("k0"), anyString());
        verify(executeSpec, atLeastOnce()).bind(eq("u0"), anyString());
        verify(executeSpec, atLeastOnce()).bind(eq("h0"), anyInt());
    }

    @Test
    @DisplayName("the last batch is sized to what is left, not to the full batch size")
    void run_shouldSizeTheFinalBatchToTheRemainder() throws InterruptedException {
        stubExistingCount(USER_COUNT - 3);
        stubInsertSucceeds(3L);

        runAndWait();

        String insert = firstInsert();
        assertThat(insert).contains(":k2").doesNotContain(":k3");
    }

    @Test
    @DisplayName("a failing insert does not bring down application startup")
    void run_shouldNotPropagateAFailure() throws InterruptedException {
        // run() is an ApplicationRunner: anything escaping it aborts the boot. Seeding is a
        // local convenience and must never be why the application refuses to start.
        stubExistingCount(0L);
        FetchSpec<Object> fetchSpec = mock(FetchSpec.class);
        when(executeSpec.fetch()).thenAnswer(invocation -> {
            done.countDown();
            return fetchSpec;
        });
        when(fetchSpec.rowsUpdated()).thenReturn(Mono.error(new IllegalStateException("refused")));

        runAndWait();
    }
}
