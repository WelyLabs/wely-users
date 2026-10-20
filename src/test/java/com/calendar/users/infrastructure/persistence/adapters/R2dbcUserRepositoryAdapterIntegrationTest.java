package com.calendar.users.infrastructure.persistence.adapters;

import com.calendar.users.domain.models.UserAbout;
import com.calendar.users.domain.models.Theme;
import com.calendar.users.domain.models.UserPreferences;

import com.calendar.users.EphemeralDatabaseCheck;
import com.calendar.users.FlywayTestDatabase;
import com.calendar.users.domain.models.AccountErasure;
import com.calendar.users.domain.models.AccountState;
import com.calendar.users.domain.models.BusinessUser;
import com.calendar.users.domain.models.DeletedUser;
import com.calendar.users.domain.models.Registration;
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
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
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
        // account_deletion references app_user, so it goes first.
        databaseClient.sql("DELETE FROM account_deletion").then().block();
        databaseClient.sql("DELETE FROM retired_handle").then().block();
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
                .verify(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("the same name with a different hashtag is allowed")
    void save_shouldAllowTheSameNameWithAnotherHashtag() {
        // The whole point of the hashtag: names are not unique on their own.
        adapter.save(aUser("alice", 1111), "keycloak-alice").block();

        StepVerifier.create(adapter.save(aUser("alice", 2222), "keycloak-other-alice"))
                .expectNextCount(1)
                .expectComplete().verify(Duration.ofSeconds(10));
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
                .verify(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("an id is found from the Keycloak subject")
    void findIdByKeycloakId_shouldReturnTheInternalId() {
        BusinessUser saved = adapter.save(aUser("alice", 1111), "keycloak-alice").block();

        StepVerifier.create(adapter.findIdByKeycloakId("keycloak-alice"))
                .expectNext(saved.id())
                .expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("an unknown subject yields nothing rather than a null id")
    void findIdByKeycloakId_shouldBeEmptyForAnUnknownSubject() {
        // UserService reads this emptiness as "provision the user", so an accidental null or a
        // zero UUID would silently stop just-in-time provisioning.
        StepVerifier.create(adapter.findIdByKeycloakId("nobody")).expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("a taken tag is reported as taken, and a free one as free")
    void existsByUserNameAndHashtag_shouldAnswerBothWays() {
        adapter.save(aUser("alice", 1111), "keycloak-alice").block();

        StepVerifier.create(adapter.existsByUserNameAndHashtag("alice", 1111))
                .expectNext(true).expectComplete().verify(Duration.ofSeconds(10));
        StepVerifier.create(adapter.existsByUserNameAndHashtag("alice", 2222))
                .expectNext(false).expectComplete().verify(Duration.ofSeconds(10));
        StepVerifier.create(adapter.existsByUserNameAndHashtag("bob", 1111))
                .expectNext(false).expectComplete().verify(Duration.ofSeconds(10));
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
                .expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("an unknown id yields nothing")
    void getBusinessUserByUserId_shouldBeEmptyForAnUnknownId() {
        StepVerifier.create(adapter.getBusinessUserByUserId(UUID.randomUUID())).expectComplete().verify(Duration.ofSeconds(10));
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
                    .verify(Duration.ofSeconds(10));

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
                .verify(Duration.ofSeconds(10));

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
                .expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("the limit bounds one pass")
    void findIdentitiesToCheck_shouldHonourTheLimit() {
        savedAgo("a", 1111, "kc-a", 60);
        savedAgo("b", 2222, "kc-b", 50);

        StepVerifier.create(adapter.findIdentitiesToCheck(LocalDateTime.now(), 1))
                .expectNextCount(1)
                .expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("deleting an orphan removes the user and its USER_CREATED, and writes USER_DELETED, together")
    void deleteOrphan_shouldDeleteTheUserAndWriteTheEvent() {
        BusinessUser orphan = savedAgo("orphan", 1111, "kc-orphan", 60);

        adapter.deleteOrphan(orphan.id()).block();

        assertThat(count("app_user")).isZero();
        assertThat(outboxTypes()).isEqualTo("USER_DELETED");
        String payload = databaseClient.sql("SELECT payload FROM outbox_event WHERE type = 'USER_DELETED'")
                .map(row -> row.get(0, String.class))
                .one()
                .block();
        assertThat(payload).matches("\\{\"userId\":\"" + orphan.id()
                + "\",\"deletedAt\":\"\\d{4}-\\d{2}-\\d{2}T[0-9:.]+Z\"}");
    }

    @Test
    @DisplayName("an orphan check read before a deletion never hard-deletes the tombstone")
    void deleteOrphan_shouldLeaveATombstoneAlone() {
        BusinessUser user = savedAgo("orphan", 1111, "kc-orphan", 60);
        adapter.eraseAccount(user.id(), now()).block();

        adapter.deleteOrphan(user.id()).block();

        assertThat(count("app_user")).isEqualTo(1L);
        assertThat(count("account_deletion")).isEqualTo(1L);
        assertThat(eventsOfType("USER_DELETED")).isEqualTo(1L);
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
                null, saved.joinedDate()), "camille").block();

        assertThat(updated.userName()).isEqualTo("camille_d");
        assertThat(updated.firstName()).isEqualTo("Cam");
        assertThat(updated.hashtag()).isEqualTo(4321);
        assertThat(outboxTypes()).isEqualTo("USER_CREATED,USER_MODIFIED");
        StepVerifier.create(adapter.findKeycloakIdByUserId(saved.id())).expectNext("kc-camille").expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("taking someone else's (username, hashtag) is USERNAME_TAKEN, and writes no event")
    void updateProfile_shouldRefuseATakenIdentity() {
        adapter.save(aUser("lea", 4321), "kc-lea").block();
        BusinessUser camille = adapter.save(aUser("camille", 4321), "kc-camille").block();

        StepVerifier.create(adapter.updateProfile(new BusinessUser(camille.id(), "lea", 4321, "Cam", "Dupont",
                        null, camille.joinedDate()), "camille"))
                .expectErrorMatches(error -> error instanceof BusinessException business
                        && business.getErrorCode() == BusinessErrorCode.USERNAME_TAKEN)
                .verify(Duration.ofSeconds(10));

        assertThat(outboxTypes()).isEqualTo("USER_CREATED,USER_CREATED");
    }

    @Test
    @DisplayName("a new user has no birth date and has not completed the signup")
    void save_shouldStartWithoutBirthDate_whenTheUserIsNew() {
        BusinessUser saved = adapter.save(aUser("new", 1212), "kc-new").block();

        BusinessUser read = adapter.getBusinessUserByUserId(saved.id()).block();
        assertThat(read.birthDate()).isNull();
        assertThat(read.registrationCompleted()).isFalse();
    }

    @Test
    @DisplayName("completing the signup stores the birth date, flips the flag and writes USER_BIRTHDAY, idempotently")
    void completeRegistration_shouldStoreTheBirthDateAndWriteTheEvent() {
        BusinessUser saved = adapter.save(aUser("lea", 1313), "kc-lea").block();
        Registration registration = new Registration(LocalDate.of(1995, 3, 12));

        BusinessUser registered = adapter.completeRegistration(saved.id(), registration).block();
        adapter.completeRegistration(saved.id(), registration).block();

        assertThat(registered.birthDate()).isEqualTo(LocalDate.of(1995, 3, 12));
        assertThat(registered.registrationCompleted()).isTrue();
        assertThat(outboxTypes()).isEqualTo("USER_CREATED,USER_BIRTHDAY,USER_BIRTHDAY");
        String payload = databaseClient.sql("SELECT payload FROM outbox_event WHERE type = 'USER_BIRTHDAY' LIMIT 1")
                .map(row -> row.get(0, String.class))
                .one()
                .block();
        assertThat(payload).contains("\"displayName\":\"First\"", "\"month\":3", "\"day\":12")
                .doesNotContain("1995");
    }

    @Test
    @DisplayName("completing the signup of an unknown user is USER_NOT_FOUND, and writes no event")
    void completeRegistration_shouldFailAsNotFound_whenTheUserIsUnknown() {
        StepVerifier.create(adapter.completeRegistration(UUID.randomUUID(), new Registration(LocalDate.of(1995, 3, 12))))
                .expectErrorMatches(error -> isBusiness(error, BusinessErrorCode.USER_NOT_FOUND))
                .verify(Duration.ofSeconds(10));

        assertThat(count("outbox_event")).isZero();
    }

    @Test
    @DisplayName("a profile update of a user with a birth date also writes USER_BIRTHDAY, for the name it shows")
    void updateProfile_shouldAlsoWriteUserBirthday_whenABirthDateIsSet() {
        BusinessUser saved = adapter.save(aUser("camille", 1414), "kc-camille").block();
        adapter.completeRegistration(saved.id(), new Registration(LocalDate.of(1995, 3, 12))).block();

        adapter.updateProfile(new BusinessUser(saved.id(), "camille", 1414, "Cam", "Dupont", null,
                saved.joinedDate()), "camille").block();

        assertThat(outboxTypes()).isEqualTo("USER_CREATED,USER_BIRTHDAY,USER_MODIFIED,USER_BIRTHDAY");
    }

    @Test
    @DisplayName("the database itself refuses a tombstone that keeps a birth date")
    void tombstoneCheck_shouldRefuseADeletedRow_whenItKeepsABirthDate() {
        BusinessUser user = aTombstone();

        StepVerifier.create(databaseClient.sql("UPDATE app_user SET birth_date = DATE '1995-03-12' WHERE id = :id")
                        .bind("id", user.id()).then())
                .expectError()
                .verify(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("the bio and interests round-trip through the text[] column, and a new user has none")
    void updateAbout_shouldRoundTripThroughTheTextArray() {
        BusinessUser user = savedAgo("about", 5555, "kc-about", 0);

        StepVerifier.create(adapter.findAbout(user.id())).expectNext(UserAbout.EMPTY).expectComplete().verify(Duration.ofSeconds(10));

        adapter.updateAbout(user.id(), new UserAbout("Partante", List.of("Rando", "Jeux de société"))).block();

        StepVerifier.create(adapter.findAbout(user.id()))
                .expectNext(new UserAbout("Partante", List.of("Rando", "Jeux de société")))
                .expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("the theme round-trips through its column, and a new user follows their device")
    void updatePreferences_shouldRoundTripTheTheme() {
        BusinessUser user = savedAgo("prefs", 6666, "kc-prefs", 0);

        StepVerifier.create(adapter.findPreferences(user.id())).expectNext(UserPreferences.DEFAULT).expectComplete().verify(Duration.ofSeconds(10));

        adapter.updatePreferences(user.id(), new UserPreferences(Theme.DARK)).block();

        StepVerifier.create(adapter.findPreferences(user.id()))
                .expectNext(new UserPreferences(Theme.DARK))
                .expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("the column refuses a theme outside the three")
    void themeColumn_shouldRefuseAnUnknownTheme() {
        BusinessUser user = savedAgo("prefs", 6667, "kc-prefs-bad", 0);

        StepVerifier.create(databaseClient.sql("UPDATE app_user SET theme = 'PINK' WHERE id = :id")
                        .bind("id", user.id()).then())
                .expectError()
                .verify(Duration.ofSeconds(10));
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

        assertThat(applied).startsWith("1", "2", "3", "4", "5", "6", "7");
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

    @Test
    @DisplayName("a database migrated to V5 by hand gets V6 on top of the baseline")
    void migrations_shouldApplyV6OnTopOfTheBaseline_whenTheDatabaseWasHandMigrated() {
        String schema = "hand_migrated_v6";
        flywayOn(schema).target(MigrationVersion.fromVersion("5")).load().migrate();
        databaseClient.sql("DROP TABLE " + schema + ".flyway_schema_history").then().block();

        try {
            MigrateResult result = flywayOn(schema)
                    .baselineOnMigrate(flywayProperties.isBaselineOnMigrate())
                    .baselineVersion(flywayProperties.getBaselineVersion())
                    .load()
                    .migrate();

            assertThat(result.migrations).extracting(migration -> migration.version).contains("6");
            Long tables = databaseClient.sql("""
                            SELECT count(*) FROM information_schema.tables
                             WHERE table_schema = :schema AND table_name IN ('retired_handle', 'account_deletion')
                            """)
                    .bind("schema", schema)
                    .map(row -> row.get(0, Long.class))
                    .one()
                    .block();
            assertThat(tables).isEqualTo(2L);
        } finally {
            databaseClient.sql("DROP SCHEMA " + schema + " CASCADE").then().block();
        }
    }

    // --- account deletion -------------------------------------------------

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    private long eventsOfType(String type) {
        return databaseClient.sql("SELECT count(*) FROM outbox_event WHERE type = :type")
                .bind("type", type)
                .map(row -> row.get(0, Long.class))
                .one()
                .block();
    }

    private Map<String, Object> rowOf(UUID id) {
        return databaseClient.sql("SELECT * FROM app_user WHERE id = :id")
                .bind("id", id)
                .fetch()
                .one()
                .block();
    }

    private BusinessUser aFullUser(String userName, int hashtag, String keycloakId) {
        BusinessUser user = adapter.save(aUser(userName, hashtag), keycloakId).block();
        adapter.updatePicture(user.id(), "avatars/" + user.id() + "/a.jpg").block();
        adapter.updateAbout(user.id(), new UserAbout("Partante", List.of("Rando"))).block();
        adapter.updatePreferences(user.id(), new UserPreferences(Theme.DARK)).block();
        adapter.completeRegistration(user.id(), new Registration(LocalDate.of(1995, 3, 12))).block();
        return user;
    }

    private BusinessUser aTombstone() {
        BusinessUser user = adapter.save(aUser("alice", 1111), "kc-alice").block();
        adapter.eraseAccount(user.id(), now()).block();
        return user;
    }

    private static boolean isBusiness(Throwable error, BusinessErrorCode code) {
        return error instanceof BusinessException business && business.getErrorCode() == code;
    }

    @Test
    @DisplayName("an erased account keeps no personal field: only the placeholders the CHECK allows")
    void eraseAccount_shouldLeaveOnlyTheTombstonePlaceholders_whenTheAccountIsErased() {
        BusinessUser user = aFullUser("alice", 1111, "kc-alice");
        Instant deletedAt = now();

        StepVerifier.create(adapter.eraseAccount(user.id(), deletedAt))
                .expectNext(AccountErasure.ERASED)
                .expectComplete().verify(Duration.ofSeconds(10));

        Map<String, Object> row = rowOf(user.id());
        assertThat(row.get("keycloak_id")).isEqualTo("deleted:" + user.id());
        assertThat(row.get("user_name")).isEqualTo("deleted-" + user.id());
        assertThat(row.get("hashtag")).isEqualTo(0);
        assertThat(row.get("first_name")).isNull();
        assertThat(row.get("last_name")).isNull();
        assertThat(row.get("profile_pic_url")).isNull();
        assertThat(row.get("bio")).isNull();
        assertThat((Object[]) row.get("interests")).isEmpty();
        assertThat(row.get("birth_date")).isNull();
        assertThat(row.get("theme")).isEqualTo("SYSTEM");
        assertThat(row.get("joined_date")).isEqualTo(LocalDateTime.ofInstant(deletedAt, ZoneOffset.UTC));
        assertThat(row.get("identity_checked_at")).isNotNull();
        assertThat(((OffsetDateTime) row.get("deleted_at")).toInstant()).isEqualTo(deletedAt);
    }

    @Test
    @DisplayName("the businessId survives: every other service keeps it as the pseudonym")
    void eraseAccount_shouldKeepTheBusinessId_whenTheAccountIsErased() {
        BusinessUser user = aTombstone();

        assertThat(count("app_user")).isEqualTo(1L);
        assertThat(rowOf(user.id()).get("id")).isEqualTo(user.id());
    }

    @Test
    @DisplayName("the job keeps the original Keycloak id, the only copy left once the row is a tombstone")
    void eraseAccount_shouldQueueTheJobWithTheOriginalKeycloakId_whenTheAccountIsErased() {
        BusinessUser user = adapter.save(aUser("alice", 1111), "kc-alice").block();
        Instant deletedAt = now();

        adapter.eraseAccount(user.id(), deletedAt).block();

        Map<String, Object> job = databaseClient.sql("SELECT * FROM account_deletion WHERE user_id = :id")
                .bind("id", user.id())
                .fetch()
                .one()
                .block();
        assertThat(job.get("keycloak_id")).isEqualTo("kc-alice");
        assertThat(((OffsetDateTime) job.get("requested_at")).toInstant()).isEqualTo(deletedAt);
        assertThat(((OffsetDateTime) job.get("next_attempt_at")).toInstant()).isEqualTo(deletedAt);
        assertThat(job.get("identity_deleted_at")).isNull();
        assertThat(job.get("media_deleted_at")).isNull();
        assertThat(job.get("attempts")).isEqualTo(0);
    }

    @Test
    @DisplayName("every other event of the user goes, pending, parked or published; other users' stay")
    void eraseAccount_shouldDeletePendingParkedAndPublishedEventsOfTheUserOnly_whenErasing() {
        BusinessUser user = adapter.save(aUser("alice", 1111), "kc-alice").block();
        adapter.updatePicture(user.id(), "avatars/" + user.id() + "/a.jpg").block();
        adapter.updatePicture(user.id(), "avatars/" + user.id() + "/b.jpg").block();
        BusinessUser other = adapter.save(aUser("bob", 2222), "kc-bob").block();
        databaseClient.sql("""
                        UPDATE outbox_event SET published_at = now()
                         WHERE id = (SELECT min(id) FROM outbox_event WHERE aggregate_id = :id)
                        """).bind("id", user.id()).then().block();
        databaseClient.sql("""
                        UPDATE outbox_event SET attempts = 5
                         WHERE id = (SELECT max(id) FROM outbox_event WHERE aggregate_id = :id)
                        """).bind("id", user.id()).then().block();

        adapter.eraseAccount(user.id(), now()).block();

        List<String> left = databaseClient.sql("SELECT aggregate_id::text || ':' || type FROM outbox_event ORDER BY id")
                .map(row -> row.get(0, String.class))
                .all()
                .collectList()
                .block();
        assertThat(left).containsExactly(other.id() + ":USER_CREATED", user.id() + ":USER_DELETED");
    }

    @Test
    @DisplayName("USER_DELETED is appended last, with deletedAt as an ISO string")
    void eraseAccount_shouldAppendUserDeletedAfterEveryEarlierEvent_withAnIsoDeletedAt() {
        BusinessUser user = adapter.save(aUser("alice", 1111), "kc-alice").block();
        adapter.save(aUser("bob", 2222), "kc-bob").block();
        Instant deletedAt = Instant.parse("2026-10-15T14:03:27.123456Z");

        adapter.eraseAccount(user.id(), deletedAt).block();

        String last = databaseClient.sql("SELECT type || ' ' || payload FROM outbox_event ORDER BY id DESC LIMIT 1")
                .map(row -> row.get(0, String.class))
                .one()
                .block();
        assertThat(last).isEqualTo("USER_DELETED {\"userId\":\"" + user.id()
                + "\",\"deletedAt\":\"2026-10-15T14:03:27.123456Z\"}");
    }

    @Test
    @DisplayName("if USER_DELETED cannot be stored, the tombstone, the handle, the job and the cleanup roll back")
    void eraseAccount_shouldRollBackEverything_whenTheEventCannotBeStored() {
        BusinessUser user = aFullUser("alice", 1111, "kc-alice");
        long eventsBefore = count("outbox_event");
        databaseClient.sql("ALTER TABLE outbox_event ADD CONSTRAINT refuse_deleted CHECK (type <> 'USER_DELETED') NOT VALID")
                .then().block();
        try {
            StepVerifier.create(adapter.eraseAccount(user.id(), now())).expectError().verify(Duration.ofSeconds(10));

            assertThat(rowOf(user.id()).get("user_name")).isEqualTo("alice");
            assertThat(rowOf(user.id()).get("deleted_at")).isNull();
            assertThat(count("retired_handle")).isZero();
            assertThat(count("account_deletion")).isZero();
            assertThat(count("outbox_event")).isEqualTo(eventsBefore);
        } finally {
            databaseClient.sql("ALTER TABLE outbox_event DROP CONSTRAINT refuse_deleted").then().block();
        }
    }

    @Test
    @DisplayName("two erasures racing on one row serialize on its lock: one USER_DELETED, one already erased")
    void eraseAccount_shouldWriteOneUserDeleted_whenTwoErasuresRace() {
        BusinessUser user = adapter.save(aUser("alice", 1111), "kc-alice").block();

        List<AccountErasure> outcomes = Flux.merge(
                        adapter.eraseAccount(user.id(), now()).subscribeOn(Schedulers.boundedElastic()),
                        adapter.eraseAccount(user.id(), now()).subscribeOn(Schedulers.boundedElastic()))
                .collectList()
                .block(Duration.ofSeconds(30));

        assertThat(outcomes).containsExactlyInAnyOrder(AccountErasure.ERASED, AccountErasure.ALREADY_ERASED);
        assertThat(eventsOfType("USER_DELETED")).isEqualTo(1L);
        assertThat(count("account_deletion")).isEqualTo(1L);
    }

    @Test
    @DisplayName("erasing a tombstone again writes nothing")
    void eraseAccount_shouldReturnAlreadyErased_whenTheAccountIsATombstone() {
        BusinessUser user = aTombstone();

        StepVerifier.create(adapter.eraseAccount(user.id(), now()))
                .expectNext(AccountErasure.ALREADY_ERASED)
                .expectComplete().verify(Duration.ofSeconds(10));

        assertThat(eventsOfType("USER_DELETED")).isEqualTo(1L);
    }

    @Test
    void eraseAccount_shouldReturnUnknownUser_whenNoRowExists() {
        StepVerifier.create(adapter.eraseAccount(UUID.randomUUID(), now()))
                .expectNext(AccountErasure.UNKNOWN_USER)
                .expectComplete().verify(Duration.ofSeconds(10));

        assertThat(count("outbox_event")).isZero();
    }

    @Test
    @DisplayName("the database itself refuses a tombstone that keeps a personal field")
    void tombstoneCheck_shouldRefuseADeletedRow_whenItKeepsAName() {
        BusinessUser user = aTombstone();

        StepVerifier.create(databaseClient.sql("UPDATE app_user SET first_name = 'Alice' WHERE id = :id")
                        .bind("id", user.id()).then())
                .expectError()
                .verify(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("a job cannot claim the identity is deleted while still holding the Keycloak id")
    void accountDeletionCheck_shouldRefuseARow_whenIdentityIsDeletedButTheKeycloakIdRemains() {
        BusinessUser user = aTombstone();

        StepVerifier.create(databaseClient.sql("UPDATE account_deletion SET identity_deleted_at = now() WHERE user_id = :id")
                        .bind("id", user.id()).then())
                .expectError()
                .verify(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("a retired handle is never free again, whatever its case")
    void existsByUserNameAndHashtag_shouldAnswerTrue_whenTheHandleIsRetiredInAnotherCase() {
        BusinessUser user = adapter.save(aUser("Alice", 1111), "kc-alice").block();
        adapter.eraseAccount(user.id(), now()).block();

        StepVerifier.create(adapter.existsByUserNameAndHashtag("alice", 1111)).expectNext(true).expectComplete().verify(Duration.ofSeconds(10));
        StepVerifier.create(adapter.existsByUserNameAndHashtag("Alice", 1111)).expectNext(true).expectComplete().verify(Duration.ofSeconds(10));
        StepVerifier.create(adapter.existsByUserNameAndHashtag("alice", 2222)).expectNext(false).expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("renaming onto a retired handle is USERNAME_TAKEN, and rolls the rename back")
    void updateProfile_shouldRefuseARetiredHandle_whenRenaming() {
        BusinessUser gone = adapter.save(aUser("lea", 4321), "kc-lea").block();
        adapter.eraseAccount(gone.id(), now()).block();
        BusinessUser camille = adapter.save(aUser("camille", 4321), "kc-camille").block();

        StepVerifier.create(adapter.updateProfile(new BusinessUser(camille.id(), "lea", 4321, "Cam", "Dupont",
                        null, camille.joinedDate()), "camille"))
                .expectErrorMatches(error -> isBusiness(error, BusinessErrorCode.USERNAME_TAKEN))
                .verify(Duration.ofSeconds(10));

        assertThat(rowOf(camille.id()).get("user_name")).isEqualTo("camille");
        assertThat(eventsOfType("USER_MODIFIED")).isZero();
    }

    @Test
    @DisplayName("a live row whose handle differs only by case from a retired one can still edit its names")
    void updateProfile_shouldNotCheckRetirement_whenTheHandleIsUnchanged() {
        BusinessUser gone = adapter.save(aUser("lea", 4321), "kc-lea").block();
        adapter.eraseAccount(gone.id(), now()).block();
        BusinessUser legacy = adapter.save(aUser("Lea", 4321), "kc-legacy").block();

        StepVerifier.create(adapter.updateProfile(new BusinessUser(legacy.id(), "Lea", 4321, "Léa", "Martin",
                        null, legacy.joinedDate()), "Lea"))
                .expectNextMatches(user -> user.firstName().equals("Léa"))
                .expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void updateProfile_shouldMatchNoRow_whenTheUserIsATombstone() {
        BusinessUser user = aTombstone();

        StepVerifier.create(adapter.updateProfile(new BusinessUser(user.id(), "alice", 1111, "A", "B", null,
                        user.joinedDate()), "alice"))
                .expectErrorMatches(error -> isBusiness(error, BusinessErrorCode.USER_NOT_FOUND))
                .verify(Duration.ofSeconds(10));
        assertThat(eventsOfType("USER_MODIFIED")).isZero();
    }

    @Test
    void updatePicture_shouldMatchNoRow_whenTheUserIsATombstone() {
        BusinessUser user = aTombstone();

        StepVerifier.create(adapter.updatePicture(user.id(), "avatars/" + user.id() + "/late.jpg"))
                .expectErrorMatches(error -> isBusiness(error, BusinessErrorCode.USER_NOT_FOUND))
                .verify(Duration.ofSeconds(10));
        assertThat(eventsOfType("USER_MODIFIED")).isZero();
    }

    @Test
    void updateAbout_shouldMatchNoRow_whenTheUserIsATombstone() {
        BusinessUser user = aTombstone();

        StepVerifier.create(adapter.updateAbout(user.id(), new UserAbout("Back", List.of())))
                .expectErrorMatches(error -> isBusiness(error, BusinessErrorCode.USER_NOT_FOUND))
                .verify(Duration.ofSeconds(10));
        StepVerifier.create(adapter.findAbout(user.id())).expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void updatePreferences_shouldMatchNoRow_whenTheUserIsATombstone() {
        BusinessUser user = aTombstone();

        StepVerifier.create(adapter.updatePreferences(user.id(), new UserPreferences(Theme.DARK)))
                .expectErrorMatches(error -> isBusiness(error, BusinessErrorCode.USER_NOT_FOUND))
                .verify(Duration.ofSeconds(10));
        StepVerifier.create(adapter.findPreferences(user.id())).expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void getBusinessUserByUserId_shouldBeEmpty_whenTheUserIsATombstone() {
        BusinessUser user = aTombstone();

        StepVerifier.create(adapter.getBusinessUserByUserId(user.id())).expectComplete().verify(Duration.ofSeconds(10));
        StepVerifier.create(adapter.findKeycloakIdByUserId(user.id())).expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("neither the original Keycloak id nor the placeholder resolves: the mapper refuses tokens")
    void findIdByKeycloakId_shouldBeEmpty_forTheOriginalAndThePlaceholderIds() {
        BusinessUser user = aTombstone();

        StepVerifier.create(adapter.findIdByKeycloakId("kc-alice")).expectComplete().verify(Duration.ofSeconds(10));
        StepVerifier.create(adapter.findIdByKeycloakId("deleted:" + user.id())).expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void findIdentitiesToCheck_shouldSkipTombstones_whenTheirCheckIsNull() {
        BusinessUser user = savedAgo("alice", 1111, "kc-alice", 60);
        adapter.eraseAccount(user.id(), now()).block();
        assertThat(rowOf(user.id()).get("identity_checked_at")).isNotNull();
        databaseClient.sql("UPDATE app_user SET identity_checked_at = NULL WHERE id = :id")
                .bind("id", user.id()).then().block();

        StepVerifier.create(adapter.findIdentitiesToCheck(LocalDateTime.now().plusDays(1), 10)).expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void findPictures_shouldLeaveTombstonesOut_whenAsked() {
        BusinessUser gone = aFullUser("alice", 1111, "kc-alice");
        BusinessUser live = aFullUser("bob", 2222, "kc-bob");
        adapter.eraseAccount(gone.id(), now()).block();

        StepVerifier.create(adapter.findPictures(List.of(gone.id(), live.id())))
                .assertNext(picture -> assertThat(picture.userId()).isEqualTo(live.id()))
                .expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void findAccountState_shouldTellLiveTombstoneAndUnknownApart() {
        BusinessUser live = adapter.save(aUser("bob", 2222), "kc-bob").block();
        BusinessUser gone = aTombstone();

        StepVerifier.create(adapter.findAccountState(live.id())).expectNext(AccountState.ACTIVE).expectComplete().verify(Duration.ofSeconds(10));
        StepVerifier.create(adapter.findAccountState(gone.id())).expectNext(AccountState.DELETED).expectComplete().verify(Duration.ofSeconds(10));
        StepVerifier.create(adapter.findAccountState(UUID.randomUUID())).expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("the keyset listing pages in (deletedAt, id) order, even across equal timestamps")
    void findDeleted_shouldPageInDeletedAtThenIdOrder_acrossEqualTimestamps() {
        Instant same = now();
        List<UUID> expected = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            BusinessUser user = adapter.save(aUser("user" + i, 1000 + i), "kc-" + i).block();
            adapter.eraseAccount(user.id(), same).block();
            expected.add(user.id());
        }
        // PostgreSQL orders uuids byte by byte, as their text does; UUID.compareTo compares signed longs.
        expected.sort(Comparator.comparing(UUID::toString));
        BusinessUser later = adapter.save(aUser("later", 4000), "kc-later").block();
        adapter.eraseAccount(later.id(), same.plusSeconds(1)).block();
        expected.add(later.id());
        adapter.save(aUser("live", 5000), "kc-live").block();

        List<DeletedUser> first = adapter.findDeleted(null, 2).collectList().block();
        List<DeletedUser> second = adapter.findDeleted(first.getLast(), 2).collectList().block();
        List<DeletedUser> third = adapter.findDeleted(second.getLast(), 2).collectList().block();

        assertThat(first).extracting(DeletedUser::userId).containsExactly(expected.get(0), expected.get(1));
        assertThat(second).extracting(DeletedUser::userId).containsExactly(expected.get(2), expected.get(3));
        assertThat(third).isEmpty();
        assertThat(first.getFirst().deletedAt()).isEqualTo(same);
    }

    @Test
    void findDeletedIds_shouldReturnOnlyTombstones_whenMixedIdsAreGiven() {
        BusinessUser live = adapter.save(aUser("bob", 2222), "kc-bob").block();
        BusinessUser gone = aTombstone();

        StepVerifier.create(adapter.findDeletedIds(List.of(live.id(), gone.id(), UUID.randomUUID())))
                .expectNext(gone.id())
                .expectComplete().verify(Duration.ofSeconds(10));
    }
}
