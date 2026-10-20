package com.calendar.users.infrastructure.persistence.adapters;

import com.calendar.users.domain.models.AccountDeletionJob;
import com.calendar.users.infrastructure.persistence.mappers.AccountDeletionEntityMapperImpl;
import com.calendar.users.infrastructure.persistence.models.entities.AccountDeletionEntity;
import com.calendar.users.infrastructure.persistence.repositories.AccountDeletionR2dbcRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unexpected database failures are mapped by InfrastructureErrorAspect, tested on its own. */
@ExtendWith(MockitoExtension.class)
class R2dbcAccountDeletionAdapterTest {

    private static final UUID USER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final Instant AT = Instant.parse("2026-10-15T14:03:27Z");
    private static final OffsetDateTime AT_UTC = OffsetDateTime.ofInstant(AT, ZoneOffset.UTC);

    @Mock
    private AccountDeletionR2dbcRepository repository;

    private R2dbcAccountDeletionAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new R2dbcAccountDeletionAdapter(repository, new AccountDeletionEntityMapperImpl(),
                Duration.ofMinutes(5));
    }

    @Test
    void claimDue_shouldPassTheLeaseInSecondsAndMapTheRows_whenJobsAreDue() {
        AccountDeletionEntity row = AccountDeletionEntity.builder()
                .userId(USER).keycloakId("kc-1").requestedAt(AT_UTC).attempts(2).build();
        when(repository.claimDue(20, 300L)).thenReturn(Flux.just(row));

        StepVerifier.create(adapter.claimDue(20))
                .expectNext(new AccountDeletionJob(USER, "kc-1", AT, false, false, 2))
                .expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void markIdentityDeleted_shouldDelegate_whenCalled() {
        when(repository.markIdentityDeleted(USER)).thenReturn(Mono.just(1L));

        StepVerifier.create(adapter.markIdentityDeleted(USER)).expectComplete().verify(Duration.ofSeconds(10));

        verify(repository).markIdentityDeleted(USER);
    }

    @Test
    void markMediaDeleted_shouldDelegate_whenCalled() {
        when(repository.markMediaDeleted(USER)).thenReturn(Mono.just(1L));

        StepVerifier.create(adapter.markMediaDeleted(USER)).expectComplete().verify(Duration.ofSeconds(10));

        verify(repository).markMediaDeleted(USER);
    }

    @Test
    void postpone_shouldDelegateWithTheGivenInstant_whenCalled() {
        when(repository.postpone(USER, AT_UTC)).thenReturn(Mono.just(1L));

        StepVerifier.create(adapter.postpone(USER, AT)).expectComplete().verify(Duration.ofSeconds(10));

        verify(repository).postpone(USER, AT_UTC);
    }

    @Test
    void recordFailure_shouldStoreTheCodeAndTheRetryTime_whenItIsShort() {
        when(repository.recordFailure(USER, "USR-TEC-001", AT_UTC)).thenReturn(Mono.just(1L));

        StepVerifier.create(adapter.recordFailure(USER, "USR-TEC-001", AT)).expectComplete().verify(Duration.ofSeconds(10));

        verify(repository).recordFailure(USER, "USR-TEC-001", AT_UTC);
    }

    @Test
    void recordFailure_shouldTruncateTheStoredError_whenItIsLong() {
        when(repository.recordFailure(eq(USER), org.mockito.ArgumentMatchers.anyString(), eq(AT_UTC)))
                .thenReturn(Mono.just(1L));

        StepVerifier.create(adapter.recordFailure(USER, "x".repeat(5000), AT)).expectComplete().verify(Duration.ofSeconds(10));

        ArgumentCaptor<String> stored = ArgumentCaptor.captor();
        verify(repository).recordFailure(eq(USER), stored.capture(), eq(AT_UTC));
        assertThat(stored.getValue()).hasSize(1000);
    }
}
