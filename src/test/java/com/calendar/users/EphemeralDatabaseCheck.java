package com.calendar.users;

import org.springframework.r2dbc.core.DatabaseClient;

import java.time.Duration;

/**
 * Refuses to let a destructive test fixture run against a database that was not started
 * for this test run.
 *
 * <h2>Why this exists</h2>
 *
 * <p>The integration tests in this module empty their tables between cases —
 * {@code DELETE FROM app_user}, {@code DELETE FROM outbox_event} — which is correct
 * against a throwaway container and catastrophic against anything else. On 2026-10-02 one
 * of those classes was pointed at the dev database to check the R2DBC bindings without a
 * Docker daemon, and it deleted the real users. Nothing in the code objected: the fixture
 * does not care what it is connected to.
 *
 * <p>So it is made to care. Any class that deletes rows calls this first.
 *
 * <h2>How it tells them apart</h2>
 *
 * <p>By how long the server has been running. A container started for this test is seconds
 * old; a deployed PostgreSQL has been up for days. There is no ambiguity in between, and
 * unlike comparing host names, ports or database names, it cannot be defeated by
 * overriding {@code spring.r2dbc.*} — which is exactly how the accident happened.
 *
 * <p>The comparison is done by PostgreSQL, not in Java. Reading the start time and
 * subtracting {@code Instant.now()} would bring the host clock into it, and a Docker VM
 * resuming from sleep can be minutes adrift from its host. Both operands here come from
 * the same server clock.
 */
public final class EphemeralDatabaseCheck {

    private static final Duration QUERY_TIMEOUT = Duration.ofSeconds(10);

    private EphemeralDatabaseCheck() {
        // Utility.
    }

    /**
     * Throws unless the connected server was started recently enough to be a throwaway.
     *
     * <p>One hour, inline in the SQL rather than bound as a parameter: a {@code Duration}
     * has no guaranteed mapping to {@code interval} in r2dbc-postgresql, and a guard whose
     * own query might fail on a type conversion is worse than no guard. The threshold is
     * generous because the signal is three orders of magnitude clear — a container lives
     * for seconds, a deployment for weeks — so a slow CI runner should never trip it.
     *
     * <p>Fails closed: an answer that cannot be read is treated as "not a container"
     * rather than waved through.
     */
    public static void verifyThrowaway(DatabaseClient databaseClient) {
        Uptime uptime = databaseClient.sql("""
                        SELECT now() - pg_postmaster_start_time() > interval '1 hour' AS long_lived,
                               date_trunc('second', now() - pg_postmaster_start_time())::text AS uptime
                        """)
                .map(row -> new Uptime(row.get("long_lived", Boolean.class), row.get("uptime", String.class)))
                .one()
                .block(QUERY_TIMEOUT);

        if (uptime == null || uptime.longLived() == null) {
            throw new IllegalStateException(
                    "Refusing to run a destructive fixture: could not establish how long the "
                            + "connected PostgreSQL has been running, so it cannot be shown to be a "
                            + "throwaway container.");
        }

        if (Boolean.TRUE.equals(uptime.longLived())) {
            throw new IllegalStateException(
                    "Refusing to run a destructive fixture against a long-lived database. The "
                            + "connected PostgreSQL has been up for " + uptime.uptime() + ", so it is "
                            + "not a container started for this test run — and this fixture deletes "
                            + "rows. If you meant to probe a deployed database, write a read-only "
                            + "check instead.");
        }
    }

    private record Uptime(Boolean longLived, String uptime) {
    }
}
