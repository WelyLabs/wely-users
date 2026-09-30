package com.calendar.users.infrastructure.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Fills {@code app_user} with synthetic rows, to see how the user search behaves at
 * scale rather than against a handful of accounts.
 *
 * <p>Restricted to the {@code local} profile. It used to include {@code test} as well,
 * which meant a test run tried to insert a million rows — and failed anyway, because
 * the insert left {@code user_name} and {@code hashtag} null while the schema declares
 * both NOT NULL.
 */
@Slf4j
@Component
@Profile("local")
public class DatabaseSeeder implements ApplicationRunner {

    private static final int USER_COUNT = 1_000_000;
    private static final int BATCH_SIZE = 5_000;

    private final R2dbcEntityTemplate template;

    public DatabaseSeeder(R2dbcEntityTemplate template) {
        this.template = template;
    }

    @Override
    public void run(ApplicationArguments args) {
        // Fire-and-forget on a dedicated scheduler: blocking startup for a million
        // inserts would hold up the whole application, and this is a local convenience.
        seedUsers()
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        unused -> { },
                        error -> log.error("User seeding failed", error),
                        () -> log.info("User seeding complete"));
    }

    private Mono<Void> seedUsers() {
        return countExistingUsers()
                .flatMap(existing -> {
                    if (existing >= USER_COUNT) {
                        log.info("app_user already holds {} rows, skipping seeding", existing);
                        return Mono.empty();
                    }

                    long toInsert = USER_COUNT - existing;
                    int batches = (int) ((toInsert + BATCH_SIZE - 1) / BATCH_SIZE);
                    log.info("Seeding {} users into app_user in {} batches", toInsert, batches);

                    return Flux.range(0, batches)
                            .concatMap(batchIndex -> {
                                int size = (int) Math.min(BATCH_SIZE, toInsert - (long) batchIndex * BATCH_SIZE);
                                return insertBatch(size)
                                        .doOnNext(rows -> log.debug("batch {}/{} inserted {} rows",
                                                batchIndex + 1, batches, rows));
                            })
                            .then();
                });
    }

    private Mono<Long> countExistingUsers() {
        return template.getDatabaseClient()
                .sql("SELECT COUNT(*) FROM app_user")
                .map((row, metadata) -> row.get(0, Long.class))
                .first()
                .defaultIfEmpty(0L);
    }

    /**
     * Inserts one multi-row batch. Every NOT NULL column is populated, including
     * {@code user_name} and {@code hashtag}, whose pair carries a unique constraint —
     * hence a name derived from the row's own UUID.
     */
    private Mono<Long> insertBatch(int size) {
        StringBuilder sql = new StringBuilder(
                "INSERT INTO app_user (keycloak_id, user_name, hashtag, profile_pic_url, joined_date) VALUES ");
        for (int i = 0; i < size; i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append("(:k").append(i)
               .append(", :u").append(i)
               .append(", :h").append(i)
               .append(", :p").append(i)
               .append(", :j").append(i).append(')');
        }

        var spec = template.getDatabaseClient().sql(sql.toString());
        for (int i = 0; i < size; i++) {
            String keycloakId = UUID.randomUUID().toString();
            spec = spec
                    .bind("k" + i, keycloakId)
                    .bind("u" + i, "seed-" + keycloakId.substring(0, 8))
                    .bind("h" + i, ThreadLocalRandom.current().nextInt(1000, 10_000))
                    .bind("p" + i, "https://example-bucket/" + keycloakId)
                    .bind("j" + i, LocalDateTime.now()
                            .minusDays(ThreadLocalRandom.current().nextInt(0, 3650)));
        }

        return spec.fetch().rowsUpdated();
    }
}
