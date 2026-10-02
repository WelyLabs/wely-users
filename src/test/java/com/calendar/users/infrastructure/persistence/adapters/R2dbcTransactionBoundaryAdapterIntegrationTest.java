package com.calendar.users.infrastructure.persistence.adapters;

import com.calendar.users.domain.models.BusinessUser;
import com.calendar.users.domain.ports.TransactionBoundary;
import com.calendar.users.domain.ports.UserEventPublisher;
import com.calendar.users.domain.ports.UserRepository;
import com.calendar.users.exception.TechnicalErrorCode;
import com.calendar.users.exception.TechnicalException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The claim the whole change rests on: the user row and its outbox row commit together,
 * or neither of them exists.
 *
 * <p>Nothing short of a real transaction can show this. The unit tests prove that
 * {@code UserService} hands both writes over as one unit and that the adapter passes it to
 * a {@code TransactionalOperator}; whether that operator actually rolls PostgreSQL back is
 * a property of the database, the driver, and of the connection travelling in the
 * subscriber context — three things a mock replaces rather than tests.
 *
 * <p>Opt-in: CI sets {@code CI=true}, locally pass {@code -Dintegration.tests=true}.
 */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
@EnabledIf("containersRequested")
class R2dbcTransactionBoundaryAdapterIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static boolean containersRequested() {
        return System.getenv("CI") != null || Boolean.getBoolean("integration.tests");
    }

    @Autowired
    private TransactionBoundary transactionBoundary;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserEventPublisher userEventPublisher;

    @Autowired
    private DatabaseClient databaseClient;

    private static boolean schemaApplied;

    @BeforeEach
    void prepareSchema() throws Exception {
        if (!schemaApplied) {
            databaseClient.sql(Files.readString(Path.of("db/migration/V1__create_app_user.sql"))).then().block();
            databaseClient.sql(Files.readString(Path.of("db/migration/V2__create_outbox_event.sql"))).then().block();
            schemaApplied = true;
        }

        databaseClient.sql("DELETE FROM app_user").then().block();
        databaseClient.sql("DELETE FROM outbox_event").then().block();
    }

    private static BusinessUser aUser() {
        return new BusinessUser(null, "alice", 1111, "First", "Last", null, LocalDateTime.now());
    }

    private Long count(String table) {
        return databaseClient.sql("SELECT count(*) FROM " + table)
                .map(row -> row.get(0, Long.class))
                .one()
                .block();
    }

    /** The unit of work {@code UserService} assembles, with nothing else around it. */
    private Mono<UUID> provision() {
        return userRepository.save(aUser(), "keycloak-alice")
                .flatMap(userEventPublisher::publishUserCreatedEvent);
    }

    @Test
    @DisplayName("the user and its event are both there after the commit")
    void atomically_shouldCommitBothWrites() {
        StepVerifier.create(transactionBoundary.atomically(provision()))
                .expectNextCount(1)
                .verifyComplete();

        assertThat(count("app_user")).isEqualTo(1L);
        assertThat(count("outbox_event")).isEqualTo(1L);

        // The row carries what the relay needs, pointing at the user that was just created.
        UUID aggregateId = databaseClient.sql("SELECT aggregate_id FROM outbox_event")
                .map(row -> row.get("aggregate_id", UUID.class))
                .one()
                .block();
        UUID userId = databaseClient.sql("SELECT id FROM app_user")
                .map(row -> row.get("id", UUID.class))
                .one()
                .block();

        assertThat(aggregateId).isEqualTo(userId);
    }

    @Test
    @DisplayName("a failure after both writes leaves neither of them behind")
    void atomically_shouldRollBackBothWrites() {
        // Without the boundary this is exactly the state the outbox exists to prevent,
        // inverted: the user committed and the event lost. Here nothing survives.
        //
        // The error that comes back is not the one thrown. atomically is a public method of
        // this package, so InfrastructureErrorAspect advises it too and maps anything that
        // is neither a BusinessException nor a TechnicalException to USR-TEC-002. Worth
        // knowing before debugging a rollback: the cause is in the log line the aspect
        // writes, not in what the caller sees.
        StepVerifier.create(transactionBoundary.atomically(
                        provision().then(Mono.error(new IllegalStateException("something later failed")))))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(TechnicalException.class);
                    assertThat(((TechnicalException) error).getErrorCode())
                            .isEqualTo(TechnicalErrorCode.DATABASE_ERROR);
                })
                .verify();

        assertThat(count("app_user")).isZero();
        assertThat(count("outbox_event")).isZero();
    }

    @Test
    @DisplayName("an event that cannot be recorded takes the user down with it")
    void atomically_shouldRefuseTheUserWhenTheEventCannotBeStored() {
        // The half of the guarantee that matters most: a user is never created unless the
        // system has also, durably, written down that it has to tell the others.
        Mono<Long> unstorableEvent = databaseClient.sql(
                        "INSERT INTO outbox_event (aggregate_id, type, payload) VALUES (:id, NULL, '{}')")
                .bind("id", UUID.randomUUID())
                .fetch()
                .rowsUpdated();

        StepVerifier.create(transactionBoundary.atomically(
                        userRepository.save(aUser(), "keycloak-alice").then(unstorableEvent)))
                .expectError()
                .verify();

        assertThat(count("app_user")).isZero();
        assertThat(count("outbox_event")).isZero();
    }

    @Test
    @DisplayName("without the boundary the two writes commit independently")
    void provision_shouldNotBeAtomicOnItsOwn() {
        // Why the boundary has to be stated by the domain rather than assumed. Run outside
        // it, each statement is its own transaction: the user survives a later failure, the
        // event is published, and nothing in the code looks any different.
        StepVerifier.create(provision().then(Mono.error(new IllegalStateException("something later failed"))))
                .expectError(IllegalStateException.class)
                .verify();

        assertThat(count("app_user")).isEqualTo(1L);
        assertThat(count("outbox_event")).isEqualTo(1L);
    }
}
