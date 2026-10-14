package com.calendar.users;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Points Flyway at a test container, so the schema an integration test runs against is built
 * by the migrations the image ships, not by a list of files kept in step by hand.
 */
public final class FlywayTestDatabase {

    private FlywayTestDatabase() {
        // Utility.
    }

    public static void migrate(DynamicPropertyRegistry registry, PostgreSQLContainer<?> postgres) {
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
    }
}
