package com.calendar.users.domain.services;

import com.calendar.users.EphemeralDatabaseCheck;
import com.calendar.users.domain.models.BusinessUser;
import com.calendar.users.domain.models.ProfileUpdate;
import com.calendar.users.domain.ports.IdentityProvider;
import com.calendar.users.domain.ports.UserRepository;
import com.calendar.users.exception.BusinessErrorCode;
import com.calendar.users.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * The guarantee {@link UserService#updateProfile} rests on, against a real PostgreSQL: the
 * identity provider is called inside the transaction, so its refusal leaves the database as it
 * was — the row and the USER_MODIFIED event both rolled back. A unit test can only check the
 * order of the calls; only a database can say whether the rollback reaches the outbox.
 *
 * <p>Opt-in: CI sets {@code CI=true}, locally pass {@code -Dintegration.tests=true}.
 */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
@EnabledIf("containersRequested")
class UserServiceIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static boolean containersRequested() {
        return System.getenv("CI") != null || Boolean.getBoolean("integration.tests");
    }

    @Autowired
    private UserService userService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DatabaseClient databaseClient;

    @MockitoBean
    private IdentityProvider identityProvider;

    private static boolean schemaApplied;

    @BeforeEach
    void prepareSchema() throws Exception {
        if (!schemaApplied) {
            for (String migration : new String[] {"V1__create_app_user.sql", "V2__create_outbox_event.sql",
                    "V3__add_identity_checked_at.sql"}) {
                databaseClient.sql(Files.readString(Path.of("db/migration/" + migration))).then().block();
            }
            schemaApplied = true;
        }
        EphemeralDatabaseCheck.verifyThrowaway(databaseClient);
        databaseClient.sql("DELETE FROM app_user").then().block();
        databaseClient.sql("DELETE FROM outbox_event").then().block();
    }

    private BusinessUser camille() {
        return userRepository.save(new BusinessUser(null, "camille", 4321, "Camille", "Durand", null,
                LocalDateTime.now()), "kc-camille").block();
    }

    private long modifiedEvents() {
        return databaseClient.sql("SELECT count(*) FROM outbox_event WHERE type = 'USER_MODIFIED'")
                .map(row -> row.get(0, Long.class)).one().block();
    }

    @Test
    @DisplayName("the identity provider accepting commits the row and the event")
    void updateProfile_shouldCommit_whenTheIdentityProviderAccepts() {
        BusinessUser camille = camille();
        when(identityProvider.updateProfile(any(), any())).thenReturn(Mono.empty());

        userService.updateProfile(camille.id(), new ProfileUpdate("camille_d", "Cam", "Dupont")).block();

        assertThat(userRepository.getBusinessUserByUserId(camille.id()).block().userName()).isEqualTo("camille_d");
        assertThat(modifiedEvents()).isEqualTo(1);
    }

    @Test
    @DisplayName("the identity provider refusing rolls back the row and the event")
    void updateProfile_shouldRollBack_whenTheIdentityProviderRefuses() {
        BusinessUser camille = camille();
        when(identityProvider.updateProfile(any(), any()))
                .thenReturn(Mono.error(new BusinessException(BusinessErrorCode.USERNAME_TAKEN)));

        StepVerifier.create(userService.updateProfile(camille.id(), new ProfileUpdate("lea", "Cam", "Dupont")))
                .expectError(BusinessException.class)
                .verify();

        BusinessUser after = userRepository.getBusinessUserByUserId(camille.id()).block();
        assertThat(after.userName()).isEqualTo("camille");
        assertThat(after.firstName()).isEqualTo("Camille");
        assertThat(modifiedEvents()).isZero();
    }
}
