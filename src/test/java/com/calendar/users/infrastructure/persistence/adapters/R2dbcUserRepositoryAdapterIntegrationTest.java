package com.calendar.users.infrastructure.persistence.adapters;

import com.calendar.users.domain.models.UserAbout;
import com.calendar.users.domain.models.Theme;
import com.calendar.users.domain.models.UserPreferences;

import com.calendar.users.EphemeralDatabaseCheck;
import com.calendar.users.FlywayTestDatabase;
import com.calendar.users.domain.models.BusinessUser;
import com.calendar.users.exception.BusinessErrorCode;
import com.calendar.users.exception.BusinessException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.flywaydb.core.api.output.MigrateResult;
import org.springframework.boot.flyway.autoconfigure.FlywayProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the R2DBC queries of {@link R2dbcUserRepositoryAdapter} against a real PostgreSQL.
 *
 * <p>Every query here is derived from a method name, so Spring Data writes the SQL and nothing
 * in the codebase states what it will be. {@code findIdByKeycloakId} returning a bare
 * {@code Mono<UUID>} is the kind of projection that either works or fails at runtime, and the
 * unit test — which mocks the repository — cannot tell which.
 *
 * <p>The schema is built by Flyway from the migrations the image ships, not from a copy in the
 * test resources. A copy would drift, and the constraint this class cares about most is declared
 * in {@code V1__create_app_user.sql}.
 *
 * <p>Opt-in: CI sets {@code CI=true}, locally pass {@code -Dintegration.tests=true}.
 */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
@EnabledIf("containersRequested")
class R2dbcUserRepositoryAdapterIntegrationTest {

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
    private R2dbcUserRepositoryAdapter adapter;

    @Autowired
    private DatabaseClient databaseClient;

    @Autowired
    private FlywayProperties flywayProperties;

    @BeforeEach
    void prepareSchema() {
        // Before any DELETE. See EphemeralDatabaseCheck: this fixture empties app_user, and
        // that is exactly how the dev users were lost.
        EphemeralDatabaseCheck.verifyThrowaway(databaseClient);

        // DELETE, not DROP: dropping a table takes its constraints with it, and the unique
        // constraint is the subject of half this class. The same mistake cost wely-chat a run
        // where every test ran against an unindexed database.
        databaseClient.sql("DELETE FROM app_user").then().block();
        databaseClient.sql("DELETE FROM outbox_event").then().block();
    }

    private Long count(String table) {
        return databaseClient.sql("SELECT count(*) FROM " + table)
                .map(row -> row.get(0, Long.class))
                .one()
                .block();
    }

    private static BusinessUser aUser(String userName, int hashtag) {
        return new BusinessUser(null, userName, hashtag, "First", "Last", null, LocalDateTime.now());
    }

    @Test
    @DisplayName("a saved user comes back with every field and a generated id")
    void save_shouldRoundTripEveryField() {
        BusinessUser saved = adapter.save(aUser("alice", 1111), "keycloak-alice").block();

        assertThat(saved).isNotNull();
        assertThat(saved.id()).isNotNull();
        assertThat(saved.userName()).isEqualTo("alice");
        assertThat(saved.hashtag()).isEqualTo(1111);
        assertThat(saved.firstName()).isEqualTo("First");
        assertThat(saved.lastName()).isEqualTo("Last");
        assertThat(saved.joinedDate()).isNotNull();
    }

    @Test
    @DisplayName("the same name and hashtag twice is refused as a business failure, not an incident")
    void save_shouldMapTheUniqueConstraintToABusinessError() {
        // unique_user_identity is what makes a user tag identify one person. The adapter maps
        // its violation to USER_ALREADY_EXISTS rather than letting it surface as a technical
        // fault — asserted here against the constraint itself rather than against a mock that
        // was told to throw.
        adapter.save(aUser("alice", 1111), "keycloak-alice").block();

        StepVerifier.create(adapter.save(aUser("alice", 1111), "keycloak-someone-else"))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(BusinessException.class);
                    assertThat(((BusinessException) error).getErrorCode())
                            .isEqualTo(BusinessErrorCode.USER_ALREADY_EXISTS);
                })
                .verify();
    }

    @Test
    @DisplayName("the same name with a different hashtag is allowed")
    void save_shouldAllowTheSameNameWithAnotherHashtag() {
        // The whole point of the hashtag: names are not unique on their own.
        adapter.save(aUser("alice", 1111), "keycloak-alice").block();

        StepVerifier.create(adapter.save(aUser("alice", 2222), "keycloak-other-alice"))
                .expectNextCount(1)
                .verifyComplete();
    }

    @Test
    @DisplayName("one Keycloak identity cannot be claimed twice")
    void save_shouldRefuseADuplicateKeycloakId() {
        // keycloak_id is UNIQUE too. The adapter maps every integrity violation to
        // USER_ALREADY_EXISTS, so this reads the same to a caller as a taken tag — worth
        // knowing, since the two are not the same problem.
        adapter.save(aUser("alice", 1111), "keycloak-alice").block();

        StepVerifier.create(adapter.save(aUser("bob", 2222), "keycloak-alice"))
                .expectError(BusinessException.class)
                .verify();
    }

    @Test
    @DisplayName("an id is found from the Keycloak subject")
    void findIdByKeycloakId_shouldReturnTheInternalId() {
        BusinessUser saved = adapter.save(aUser("alice", 1111), "keycloak-alice").block();

        StepVerifier.create(adapter.findIdByKeycloakId("keycloak-alice"))
                .expectNext(saved.id())
                .verifyComplete();
    }

    @Test
    @DisplayName("an unknown subject yields nothing rather than a null id")
    void findIdByKeycloakId_shouldBeEmptyForAnUnknownSubject() {
        // UserService reads this emptiness as "provision the user", so an accidental null or a
        // zero UUID would silently stop just-in-time provisioning.
        StepVerifier.create(adapter.findIdByKeycloakId("nobody")).verifyComplete();
    }

    @Test
    @DisplayName("a taken tag is reported as taken, and a free one as free")
    void existsByUserNameAndHashtag_shouldAnswerBothWays() {
        adapter.save(aUser("alice", 1111), "keycloak-alice").block();

        StepVerifier.create(adapter.existsByUserNameAndHashtag("alice", 1111))
                .expectNext(true).verifyComplete();
        StepVerifier.create(adapter.existsByUserNameAndHashtag("alice", 2222))
                .expectNext(false).verifyComplete();
        StepVerifier.create(adapter.existsByUserNameAndHashtag("bob", 1111))
                .expectNext(false).verifyComplete();
    }

    @Test
    @DisplayName("a user is read back by internal id")
    void getBusinessUserByUserId_shouldReturnTheUser() {
        BusinessUser saved = adapter.save(aUser("alice", 1111), "keycloak-alice").block();

        StepVerifier.create(adapter.getBusinessUserByUserId(saved.id()))
                .assertNext(found -> {
                    assertThat(found.userName()).isEqualTo("alice");
                    assertThat(found.hashtag()).isEqualTo(1111);
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("an unknown id yields nothing")
    void getBusinessUserByUserId_shouldBeEmptyForAnUnknownId() {
        StepVerifier.create(adapter.getBusinessUserByUserId(UUID.randomUUID())).verifyComplete();
    }

    @Test
    @DisplayName("the Keycloak subject is stored, though the domain model never carries it")
    void save_shouldPersistTheKeycloakIdOutsideTheDomainModel() {
        // BusinessUser has no keycloakId: the link to the identity provider is infrastructure.
        // The adapter sets it on the entity, and nothing but a real row proves it landed.
        adapter.save(aUser("alice", 1111), "keycloak-alice").block();

        String stored = databaseClient.sql("SELECT keycloak_id FROM app_user WHERE user_name = 'alice'")
                .map(row -> row.get("keycloak_id", String.class))
                .one()
                .block();

        assertThat(stored).isEqualTo("keycloak-alice");
    }

    @Test
    @DisplayName("saving a user also records its USER_CREATED event")
    void save_shouldRecordTheUserCreatedEvent() {
        BusinessUser saved = adapter.save(aUser("alice", 1111), "keycloak-alice").block();

        assertThat(count("app_user")).isEqualTo(1L);
        assertThat(count("outbox_event")).isEqualTo(1L);
        UUID aggregateId = databaseClient.sql("SELECT aggregate_id FROM outbox_event WHERE type = 'USER_CREATED'")
                .map(row -> row.get("aggregate_id", UUID.class))
                .one()
                .block();
        assertThat(aggregateId).isEqualTo(saved.id());
    }

    @Test
    @DisplayName("if the event cannot be recorded, the user is rolled back too")
    void save_shouldRollBackTheUserWhenTheEventCannotBeStored() {
        // A constraint that refuses every new outbox row makes the second insert fail.
        databaseClient.sql("ALTER TABLE outbox_event ADD CONSTRAINT refuse_all CHECK (false) NOT VALID")
                .then().block();
        try {
            StepVerifier.create(adapter.save(aUser("alice", 1111), "keycloak-alice"))
                    .expectError()
                    .verify();

            assertThat(count("app_user")).isZero();
            assertThat(count("outbox_event")).isZero();
        } finally {
            databaseClient.sql("ALTER TABLE outbox_event DROP CONSTRAINT refuse_all").then().block();
        }
    }

    @Test
    @DisplayName("a refused user leaves no event behind")
    void save_shouldNotRecordAnEventForARefusedUser() {
        adapter.save(aUser("alice", 1111), "keycloak-alice").block();

        StepVerifier.create(adapter.save(aUser("alice", 1111), "keycloak-bob"))
                .expectError(BusinessException.class)
                .verify();

        assertThat(count("app_user")).isEqualTo(1L);
        assertThat(count("outbox_event")).isEqualTo(1L);
    }

    private BusinessUser savedAgo(String userName, int hashtag, String keycloakId, long minutesAgo) {
        return adapter.save(new BusinessUser(null, userName, hashtag, "First", "Last", null,
                LocalDateTime.now().minusMinutes(minutesAgo)), keycloakId).block();
    }

    @Test
    @DisplayName("only unchecked users older than the cutoff are returned, oldest first")
    void findIdentitiesToCheck_shouldSkipRecentAndCheckedUsers() {
        BusinessUser older = savedAgo("older", 1111, "kc-older", 60);
        BusinessUser old = savedAgo("old", 2222, "kc-old", 30);
        savedAgo("recent", 3333, "kc-recent", 1);
        BusinessUser checked = savedAgo("checked", 4444, "kc-checked", 90);
        adapter.markIdentityChecked(checked.id()).block();

        StepVerifier.create(adapter.findIdentitiesToCheck(LocalDateTime.now().minusMinutes(15), 10))
                .assertNext(identity -> {
                    assertThat(identity.userId()).isEqualTo(older.id());
                    assertThat(identity.keycloakId()).isEqualTo("kc-older");
                })
                .assertNext(identity -> assertThat(identity.userId()).isEqualTo(old.id()))
                .verifyComplete();
    }

    @Test
    @DisplayName("the limit bounds one pass")
    void findIdentitiesToCheck_shouldHonourTheLimit() {
        savedAgo("a", 1111, "kc-a", 60);
        savedAgo("b", 2222, "kc-b", 50);

        StepVerifier.create(adapter.findIdentitiesToCheck(LocalDateTime.now(), 1))
                .expectNextCount(1)
                .verifyComplete();
    }

    @Test
    @DisplayName("deleting an orphan removes the user and writes USER_DELETED, together")
    void deleteOrphan_shouldDeleteTheUserAndWriteTheEvent() {
        BusinessUser orphan = savedAgo("orphan", 1111, "kc-orphan", 60);

        adapter.deleteOrphan(orphan.id()).block();

        assertThat(count("app_user")).isZero();
        String payload = databaseClient.sql("SELECT payload FROM outbox_event WHERE type = 'USER_DELETED'")
                .map(row -> row.get(0, String.class))
                .one()
                .block();
        assertThat(payload).isEqualTo("{\"userId\":\"" + orphan.id() + "\"}");
    }

    // --- updateProfile ---------------------------------------------------

    private String outboxTypes() {
        return databaseClient.sql("SELECT string_agg(type, ',' ORDER BY id) FROM outbox_event")
                .map(row -> row.get(0, String.class))
                .one()
                .block();
    }

    @Test
    @DisplayName("a profile update changes the names, keeps the rest, and writes USER_MODIFIED")
    void updateProfile_shouldUpdateTheRowAndWriteTheEvent() {
        BusinessUser saved = adapter.save(aUser("camille", 4321), "kc-camille").block();

        BusinessUser updated = adapter.updateProfile(new BusinessUser(saved.id(), "camille_d", 4321, "Cam", "Dupont",
                null, saved.joinedDate())).block();

        assertThat(updated.userName()).isEqualTo("camille_d");
        assertThat(updated.firstName()).isEqualTo("Cam");
        assertThat(updated.hashtag()).isEqualTo(4321);
        assertThat(outboxTypes()).isEqualTo("USER_CREATED,USER_MODIFIED");
        StepVerifier.create(adapter.findKeycloakIdByUserId(saved.id())).expectNext("kc-camille").verifyComplete();
    }

    @Test
    @DisplayName("taking someone else's (username, hashtag) is USERNAME_TAKEN, and writes no event")
    void updateProfile_shouldRefuseATakenIdentity() {
        adapter.save(aUser("lea", 4321), "kc-lea").block();
        BusinessUser camille = adapter.save(aUser("camille", 4321), "kc-camille").block();

        StepVerifier.create(adapter.updateProfile(new BusinessUser(camille.id(), "lea", 4321, "Cam", "Dupont",
                        null, camille.joinedDate())))
                .expectErrorMatches(error -> error instanceof BusinessException business
                        && business.getErrorCode() == BusinessErrorCode.USERNAME_TAKEN)
                .verify();

        assertThat(outboxTypes()).isEqualTo("USER_CREATED,USER_CREATED");
    }

    @Test
    @DisplayName("the bio and interests round-trip through the text[] column, and a new user has none")
    void updateAbout_shouldRoundTripThroughTheTextArray() {
        BusinessUser user = savedAgo("about", 5555, "kc-about", 0);

        StepVerifier.create(adapter.findAbout(user.id())).expectNext(UserAbout.EMPTY).verifyComplete();

        adapter.updateAbout(user.id(), new UserAbout("Partante", List.of("Rando", "Jeux de société"))).block();

        StepVerifier.create(adapter.findAbout(user.id()))
                .expectNext(new UserAbout("Partante", List.of("Rando", "Jeux de société")))
                .verifyComplete();
    }

    @Test
    @DisplayName("the theme round-trips through its column, and a new user follows their device")
    void updatePreferences_shouldRoundTripTheTheme() {
        BusinessUser user = savedAgo("prefs", 6666, "kc-prefs", 0);

        StepVerifier.create(adapter.findPreferences(user.id())).expectNext(UserPreferences.DEFAULT).verifyComplete();

        adapter.updatePreferences(user.id(), new UserPreferences(Theme.DARK)).block();

        StepVerifier.create(adapter.findPreferences(user.id()))
                .expectNext(new UserPreferences(Theme.DARK))
                .verifyComplete();
    }

    @Test
    @DisplayName("the column refuses a theme outside the three")
    void themeColumn_shouldRefuseAnUnknownTheme() {
        BusinessUser user = savedAgo("prefs", 6667, "kc-prefs-bad", 0);

        StepVerifier.create(databaseClient.sql("UPDATE app_user SET theme = 'PINK' WHERE id = :id")
                        .bind("id", user.id()).then())
                .expectError()
                .verify();
    }

    @Test
    @DisplayName("an empty database gets every shipped migration from V1")
    void migrations_shouldBuildAnEmptyDatabaseFromV1() {
        List<String> applied = databaseClient.sql(
                        "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank")
                .map(row -> row.get(0, String.class))
                .all()
                .collectList()
                .block();

        assertThat(applied).startsWith("1", "2", "3", "4", "5");
    }

    @Test
    @DisplayName("a database migrated to V5 by hand is baselined, nothing up to V5 runs again")
    void migrations_shouldBaselineAHandMigratedDatabase() {
        String schema = "hand_migrated";
        // V1 to V5 as they were applied before Flyway: the tables are there, the history is not.
        flywayOn(schema).target(MigrationVersion.fromVersion("5")).load().migrate();
        databaseClient.sql("DROP TABLE " + schema + ".flyway_schema_history").then().block();

        try {
            MigrateResult result = flywayOn(schema)
                    .baselineOnMigrate(flywayProperties.isBaselineOnMigrate())
                    .baselineVersion(flywayProperties.getBaselineVersion())
                    .load()
                    .migrate();

            assertThat(result.migrations)
                    .allSatisfy(migration -> assertThat(MigrationVersion.fromVersion(migration.version))
                            .isGreaterThan(MigrationVersion.fromVersion("5")));
        } finally {
            databaseClient.sql("DROP SCHEMA " + schema + " CASCADE").then().block();
        }
    }

    private static FluentConfiguration flywayOn(String schema) {
        return Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema);
    }
}
