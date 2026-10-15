package com.calendar.users.infrastructure.persistence.mappers;

import com.calendar.users.domain.models.AccountDeletionJob;
import com.calendar.users.domain.models.DeletedUser;
import com.calendar.users.infrastructure.persistence.models.DeletedUserRow;
import com.calendar.users.infrastructure.persistence.models.entities.AccountDeletionEntity;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AccountDeletionEntityMapperTest {

    private static final UUID USER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final OffsetDateTime REQUESTED = OffsetDateTime.of(2026, 10, 15, 16, 3, 27, 0, ZoneOffset.ofHours(2));

    private final AccountDeletionEntityMapper mapper = new AccountDeletionEntityMapperImpl();

    @Test
    void toJob_shouldFlagStepsDone_whenTimestampsAreSet() {
        AccountDeletionEntity entity = AccountDeletionEntity.builder()
                .userId(USER).requestedAt(REQUESTED).identityDeletedAt(REQUESTED).mediaDeletedAt(REQUESTED)
                .attempts(3).build();

        assertThat(mapper.toJob(entity))
                .isEqualTo(new AccountDeletionJob(USER, null, Instant.parse("2026-10-15T14:03:27Z"), true, true, 3));
    }

    @Test
    void toJob_shouldKeepTheSubjectAndFlagStepsLeft_whenTimestampsAreNull() {
        AccountDeletionEntity entity = AccountDeletionEntity.builder()
                .userId(USER).keycloakId("kc-1").requestedAt(REQUESTED).attempts(0).build();

        assertThat(mapper.toJob(entity))
                .isEqualTo(new AccountDeletionJob(USER, "kc-1", REQUESTED.toInstant(), false, false, 0));
    }

    @Test
    void toJob_shouldReadNullAttemptsAsZero() {
        AccountDeletionEntity entity = AccountDeletionEntity.builder().userId(USER).requestedAt(REQUESTED).build();

        assertThat(mapper.toJob(entity).attempts()).isZero();
    }

    @Test
    void toDeletedUser_shouldConvertTheTimestamp() {
        assertThat(mapper.toDeletedUser(new DeletedUserRow(USER, REQUESTED)))
                .isEqualTo(new DeletedUser(USER, Instant.parse("2026-10-15T14:03:27Z")));
    }

    @Test
    void toInstant_shouldStayNull_whenTheTimestampIsNull() {
        assertThat(mapper.toInstant(null)).isNull();
        assertThat(mapper.toJob(null)).isNull();
        assertThat(mapper.toDeletedUser(null)).isNull();
    }
}
