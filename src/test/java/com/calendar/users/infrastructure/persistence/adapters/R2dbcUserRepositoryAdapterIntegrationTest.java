package com.calendar.users.infrastructure.persistence.adapters;

import com.calendar.users.domain.models.BusinessUser;
import com.calendar.users.exception.BusinessErrorCode;
import com.calendar.users.exception.BusinessException;
import org.junit.jupiter.api.BeforeAll;
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
import reactor.test.StepVerifier;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
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
 * <p>The schema comes from {@code db/migration/V1__create_app_user.sql}, read from disk rather
 * than copied into the test resources. A copy would drift, and the constraint this class cares
 * about most is declared in that file.
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

    @Autowired
    private R2dbcUserRepositoryAdapter adapter;

    @Autowired
    private DatabaseClient databaseClient;

    private static boolean schemaApplied;

    @BeforeEach
    void prepareSchema() throws Exception {
        if (!schemaApplied) {
            String ddl = Files.readString(Path.of("db/migration/V1__create_app_user.sql"));
            databaseClient.sql(ddl).then().block();
            schemaApplied = true;
        }

        // DELETE, not DROP: dropping a table takes its constraints with it, and the unique
        // constraint is the subject of half this class. The same mistake cost wely-chat a run
        // where every test ran against an unindexed database.
        databaseClient.sql("DELETE FROM app_user").then().block();
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
}
