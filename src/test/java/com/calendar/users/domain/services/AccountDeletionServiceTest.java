package com.calendar.users.domain.services;

import com.calendar.users.domain.models.AccountDeletionJob;
import com.calendar.users.domain.models.AccountDeletionPolicy;
import com.calendar.users.domain.models.AccountErasure;
import com.calendar.users.domain.models.AccountState;
import com.calendar.users.domain.models.DeletedUser;
import com.calendar.users.domain.models.DeletedUsersPage;
import com.calendar.users.domain.models.DeletionProgress;
import com.calendar.users.domain.ports.AccountDeletionRepository;
import com.calendar.users.domain.ports.IdentityProvider;
import com.calendar.users.domain.ports.MediaStore;
import com.calendar.users.domain.ports.UserRepository;
import com.calendar.users.exception.BusinessErrorCode;
import com.calendar.users.exception.BusinessException;
import com.calendar.users.exception.TechnicalErrorCode;
import com.calendar.users.exception.TechnicalException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountDeletionServiceTest {

    private static final UUID USER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID DEMO = UUID.fromString("89a380b8-f66a-44d0-b9d9-616241080430");
    private static final Instant NOW = Instant.parse("2026-10-15T14:03:27.123456789Z");
    private static final Instant SIGNED_IN = NOW.minusSeconds(30);
    private static final String AVATARS = "avatars/" + USER + "/";

    @Mock private UserRepository users;
    @Mock private AccountDeletionRepository jobs;
    @Mock private IdentityProvider identityProvider;
    @Mock private MediaStore mediaStore;

    private final AccountDeletionPolicy policy = policy(true);

    private static AccountDeletionPolicy policy(boolean enabled) {
        return new AccountDeletionPolicy(enabled, Set.of(DEMO), Duration.ofMinutes(5), Duration.ofSeconds(60),
                Duration.ofSeconds(5), Duration.ofHours(1), Duration.ofMinutes(10));
    }

    private AccountDeletionService switchedOff() {
        return new AccountDeletionService(users, jobs, identityProvider, mediaStore, policy(false),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private AccountDeletionService service;

    @BeforeEach
    void setUp() {
        service = new AccountDeletionService(users, jobs, identityProvider, mediaStore, policy,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static boolean isBusiness(Throwable error, BusinessErrorCode code) {
        return error instanceof BusinessException business && business.getErrorCode() == code;
    }

    private void givenALiveAccount() {
        when(users.findAccountState(USER)).thenReturn(Mono.just(AccountState.ACTIVE));
    }

    // --- requestDeletion ------------------------------------------------------

    @Test
    void requestDeletion_shouldRefuse_whenDeletionIsDisabled() {
        StepVerifier.create(switchedOff().requestDeletion(USER, SIGNED_IN))
                .expectErrorMatches(error -> isBusiness(error, BusinessErrorCode.ACCOUNT_DELETION_DISABLED))
                .verify();

        verifyNoInteractions(users, jobs, identityProvider, mediaStore);
    }

    @Test
    void requestDeletion_shouldCheckTheSwitchBeforeTheDemoGuard_whenDeletionIsDisabled() {
        StepVerifier.create(switchedOff().requestDeletion(DEMO, null))
                .expectErrorMatches(error -> isBusiness(error, BusinessErrorCode.ACCOUNT_DELETION_DISABLED))
                .verify();
    }

    @Test
    void requestDeletion_shouldFailWithAccountProtected_whenTheUserIsADemoAccount() {
        StepVerifier.create(service.requestDeletion(DEMO, SIGNED_IN))
                .expectErrorMatches(error -> isBusiness(error, BusinessErrorCode.ACCOUNT_PROTECTED))
                .verify();

        verifyNoInteractions(users);
    }

    @Test
    void requestDeletion_shouldCheckTheDemoGuardFirst_whenAuthTimeIsAlsoMissing() {
        StepVerifier.create(service.requestDeletion(DEMO, null))
                .expectErrorMatches(error -> isBusiness(error, BusinessErrorCode.ACCOUNT_PROTECTED))
                .verify();
    }

    @Test
    void requestDeletion_shouldRequireReauthentication_whenAuthTimeIsMissing() {
        givenALiveAccount();

        StepVerifier.create(service.requestDeletion(USER, null))
                .expectErrorMatches(error -> isBusiness(error, BusinessErrorCode.REAUTHENTICATION_REQUIRED))
                .verify();

        verify(users, never()).eraseAccount(any(), any());
    }

    @Test
    void requestDeletion_shouldRequireReauthentication_whenAuthTimeIsOlderThanFiveMinutes() {
        givenALiveAccount();

        StepVerifier.create(service.requestDeletion(USER, NOW.minusSeconds(301)))
                .expectErrorMatches(error -> isBusiness(error, BusinessErrorCode.REAUTHENTICATION_REQUIRED))
                .verify();

        verify(users, never()).eraseAccount(any(), any());
    }

    @Test
    void requestDeletion_shouldAccept_whenAuthTimeIsExactlyFiveMinutesOld() {
        givenALiveAccount();
        when(users.eraseAccount(eq(USER), any())).thenReturn(Mono.just(AccountErasure.ERASED));

        StepVerifier.create(service.requestDeletion(USER, NOW.minusSeconds(300))).verifyComplete();
    }

    @Test
    void requestDeletion_shouldRequireReauthentication_whenAuthTimeIsMoreThanSixtySecondsAhead() {
        givenALiveAccount();

        StepVerifier.create(service.requestDeletion(USER, NOW.plusSeconds(61)))
                .expectErrorMatches(error -> isBusiness(error, BusinessErrorCode.REAUTHENTICATION_REQUIRED))
                .verify();
    }

    @Test
    void requestDeletion_shouldAccept_whenAuthTimeIsAheadWithinTheSkew() {
        givenALiveAccount();
        when(users.eraseAccount(eq(USER), any())).thenReturn(Mono.just(AccountErasure.ERASED));

        StepVerifier.create(service.requestDeletion(USER, NOW.plusSeconds(60))).verifyComplete();
    }

    @Test
    void requestDeletion_shouldEraseWithTheClockInstantTruncatedToMicros_whenAuthenticationIsRecent() {
        givenALiveAccount();
        when(users.eraseAccount(eq(USER), any())).thenReturn(Mono.just(AccountErasure.ERASED));

        StepVerifier.create(service.requestDeletion(USER, SIGNED_IN)).verifyComplete();

        verify(users).eraseAccount(USER, Instant.parse("2026-10-15T14:03:27.123456Z"));
    }

    @Test
    void requestDeletion_shouldComplete_whenTheAccountIsAlreadyErased() {
        // Two requests racing past the state check: the second finds the tombstone under the row lock.
        givenALiveAccount();
        when(users.eraseAccount(eq(USER), any())).thenReturn(Mono.just(AccountErasure.ALREADY_ERASED));

        StepVerifier.create(service.requestDeletion(USER, SIGNED_IN)).verifyComplete();
    }

    @Test
    void requestDeletion_shouldAnswerAcceptedForATombstone_evenWithAStaleAuthTime() {
        // The 202 of the first deletion was lost: the retry must not send the user back to a login that fails.
        when(users.findAccountState(USER)).thenReturn(Mono.just(AccountState.DELETED));

        StepVerifier.create(service.requestDeletion(USER, NOW.minus(Duration.ofHours(1)))).verifyComplete();

        verify(users, never()).eraseAccount(any(), any());
    }

    @Test
    void requestDeletion_shouldFailWithUserNotFound_whenNoRowExists() {
        when(users.findAccountState(USER)).thenReturn(Mono.empty());
        when(users.eraseAccount(eq(USER), any())).thenReturn(Mono.just(AccountErasure.UNKNOWN_USER));

        StepVerifier.create(service.requestDeletion(USER, SIGNED_IN))
                .expectErrorMatches(error -> isBusiness(error, BusinessErrorCode.USER_NOT_FOUND))
                .verify();
    }

    @Test
    void requestDeletion_shouldPropagateATechnicalFailure_whenTheErasureFails() {
        givenALiveAccount();
        when(users.eraseAccount(eq(USER), any()))
                .thenReturn(Mono.error(new TechnicalException(TechnicalErrorCode.DATABASE_ERROR)));

        StepVerifier.create(service.requestDeletion(USER, SIGNED_IN))
                .expectError(TechnicalException.class)
                .verify();
    }

    @Test
    void requestDeletion_shouldNotCallTheIdentityProviderOrMediaStore_whenAccepted() {
        givenALiveAccount();
        when(users.eraseAccount(eq(USER), any())).thenReturn(Mono.just(AccountErasure.ERASED));

        StepVerifier.create(service.requestDeletion(USER, SIGNED_IN)).verifyComplete();

        verifyNoInteractions(identityProvider, mediaStore, jobs);
    }

    @Test
    void requestDeletion_shouldNotReadTheClockBeforeSubscription_whenBuilt() {
        Clock clock = mock(Clock.class);
        AccountDeletionService lazy = new AccountDeletionService(users, jobs, identityProvider, mediaStore,
                policy, clock);

        lazy.requestDeletion(USER, SIGNED_IN);

        verifyNoInteractions(clock, users);
    }

    // --- isDeletionAllowed ----------------------------------------------------

    @Test
    void isDeletionAllowed_shouldFailWithAccountDeletionDisabledWithoutReadingTheDatabase_whenSwitchedOff() {
        StepVerifier.create(switchedOff().isDeletionAllowed(USER))
                .expectErrorMatches(error -> isBusiness(error, BusinessErrorCode.ACCOUNT_DELETION_DISABLED))
                .verify();

        verifyNoInteractions(users);
    }

    @Test
    void isDeletionAllowed_shouldAnswerFalseWithoutReadingTheDatabase_whenTheUserIsProtected() {
        StepVerifier.create(service.isDeletionAllowed(DEMO)).expectNext(false).verifyComplete();

        verifyNoInteractions(users);
    }

    @Test
    void isDeletionAllowed_shouldAnswerTrue_whenTheAccountIsActive() {
        givenALiveAccount();

        StepVerifier.create(service.isDeletionAllowed(USER)).expectNext(true).verifyComplete();
    }

    @Test
    void isDeletionAllowed_shouldFailWithUserDeleted_whenTheAccountIsATombstone() {
        when(users.findAccountState(USER)).thenReturn(Mono.just(AccountState.DELETED));

        StepVerifier.create(service.isDeletionAllowed(USER))
                .expectErrorMatches(error -> isBusiness(error, BusinessErrorCode.USER_DELETED))
                .verify();
    }

    @Test
    void isDeletionAllowed_shouldFailWithUserNotFound_whenTheUserIsUnknown() {
        when(users.findAccountState(USER)).thenReturn(Mono.empty());

        StepVerifier.create(service.isDeletionAllowed(USER))
                .expectErrorMatches(error -> isBusiness(error, BusinessErrorCode.USER_NOT_FOUND))
                .verify();
    }

    // --- deletedAmong ---------------------------------------------------------

    @Test
    void deletedAmong_shouldReturnOnlyTheTombstonedIds_whenSomeAreDeleted() {
        UUID live = UUID.randomUUID();
        when(users.findDeletedIds(List.of(USER, live))).thenReturn(Flux.just(USER));

        StepVerifier.create(service.deletedAmong(List.of(USER, live))).expectNext(Set.of(USER)).verifyComplete();
    }

    @Test
    void deletedAmong_shouldNotQuery_whenNoIdIsGiven() {
        StepVerifier.create(service.deletedAmong(List.of())).expectNext(Set.of()).verifyComplete();

        verifyNoInteractions(users);
    }

    // --- deletedUsers ---------------------------------------------------------

    private static DeletedUser deleted(int second) {
        return new DeletedUser(UUID.randomUUID(), NOW.plusSeconds(second));
    }

    @Test
    void deletedUsers_shouldReturnANextCursor_whenMoreTombstonesRemain() {
        DeletedUser a = deleted(1);
        DeletedUser b = deleted(2);
        DeletedUser c = deleted(3);
        when(users.findDeleted(null, 3)).thenReturn(Flux.just(a, b, c));

        StepVerifier.create(service.deletedUsers(null, 2))
                .expectNext(new DeletedUsersPage(List.of(a, b), b))
                .verifyComplete();
    }

    @Test
    void deletedUsers_shouldReturnNoNextCursor_whenThePageIsTheLast() {
        DeletedUser after = deleted(0);
        DeletedUser a = deleted(1);
        when(users.findDeleted(after, 3)).thenReturn(Flux.just(a));

        StepVerifier.create(service.deletedUsers(after, 2))
                .expectNext(new DeletedUsersPage(List.of(a), null))
                .verifyComplete();
    }

    @Test
    void deletedUsers_shouldStartFromTheFirstTombstone_whenNoCursorIsGiven() {
        when(users.findDeleted(null, 501)).thenReturn(Flux.empty());

        StepVerifier.create(service.deletedUsers(null, 500))
                .expectNext(new DeletedUsersPage(List.of(), null))
                .verifyComplete();

        verify(users).findDeleted(null, 501);
    }

    // --- processDueDeletions --------------------------------------------------

    private static AccountDeletionJob job(Instant requestedAt, boolean identityDeleted, boolean mediaDeleted,
                                          int attempts) {
        return new AccountDeletionJob(USER, identityDeleted ? null : "kc-1", requestedAt, identityDeleted,
                mediaDeleted, attempts);
    }

    private void givenTheJobs(AccountDeletionJob... due) {
        when(jobs.claimDue(20)).thenReturn(Flux.just(due));
    }

    @Test
    void processDueDeletions_shouldDeleteTheSubjectPurgeTheAvatarsAndWait_whenTheJobIsFresh() {
        givenTheJobs(job(NOW.minusSeconds(5), false, false, 0));
        when(identityProvider.deleteSubject("kc-1")).thenReturn(Mono.empty());
        when(jobs.markIdentityDeleted(USER)).thenReturn(Mono.empty());
        when(mediaStore.deletePrefix(AVATARS)).thenReturn(Mono.empty());
        when(jobs.postpone(USER, NOW.minusSeconds(5).plus(Duration.ofMinutes(10)))).thenReturn(Mono.empty());

        StepVerifier.create(service.processDueDeletions(20))
                .expectNext(new DeletionProgress(USER, DeletionProgress.Status.WAITING, 0, null))
                .verifyComplete();

        verify(jobs).markIdentityDeleted(USER);
        verify(jobs, never()).markMediaDeleted(any());
    }

    @Test
    void processDueDeletions_shouldPurgeOnlyTheUsersAvatarPrefix_whenPurging() {
        givenTheJobs(job(NOW.minusSeconds(5), true, false, 0));
        when(mediaStore.deletePrefix(anyString())).thenReturn(Mono.empty());
        when(jobs.postpone(eq(USER), any())).thenReturn(Mono.empty());

        StepVerifier.create(service.processDueDeletions(20)).expectNextCount(1).verifyComplete();

        verify(mediaStore).deletePrefix("avatars/11111111-1111-1111-1111-111111111111/");
    }

    @Test
    void processDueDeletions_shouldMarkMediaDeleted_whenThePurgeRunsAfterTheSettleDelay() {
        givenTheJobs(job(NOW.minus(Duration.ofMinutes(10)), true, false, 0));
        when(mediaStore.deletePrefix(AVATARS)).thenReturn(Mono.empty());
        when(jobs.markMediaDeleted(USER)).thenReturn(Mono.empty());

        StepVerifier.create(service.processDueDeletions(20))
                .expectNext(new DeletionProgress(USER, DeletionProgress.Status.COMPLETED, 0, null))
                .verifyComplete();

        verify(jobs, never()).postpone(any(), any());
    }

    @Test
    void processDueDeletions_shouldSkipTheIdentityStep_whenTheSubjectIsAlreadyDeleted() {
        givenTheJobs(job(NOW.minus(Duration.ofHours(1)), true, false, 2));
        when(mediaStore.deletePrefix(AVATARS)).thenReturn(Mono.empty());
        when(jobs.markMediaDeleted(USER)).thenReturn(Mono.empty());

        StepVerifier.create(service.processDueDeletions(20)).expectNextCount(1).verifyComplete();

        verifyNoInteractions(identityProvider);
    }

    @Test
    void processDueDeletions_shouldReportCompleted_whenBothStepsAreDone() {
        givenTheJobs(job(NOW.minus(Duration.ofMinutes(11)), false, false, 0));
        when(identityProvider.deleteSubject("kc-1")).thenReturn(Mono.empty());
        when(jobs.markIdentityDeleted(USER)).thenReturn(Mono.empty());
        when(mediaStore.deletePrefix(AVATARS)).thenReturn(Mono.empty());
        when(jobs.markMediaDeleted(USER)).thenReturn(Mono.empty());

        StepVerifier.create(service.processDueDeletions(20))
                .expectNext(new DeletionProgress(USER, DeletionProgress.Status.COMPLETED, 0, null))
                .verifyComplete();
    }

    @Test
    void processDueDeletions_shouldFinishQueuedJobs_whenDeletionIsDisabled() {
        givenTheJobs(job(NOW.minus(Duration.ofMinutes(11)), false, false, 0));
        when(identityProvider.deleteSubject("kc-1")).thenReturn(Mono.empty());
        when(jobs.markIdentityDeleted(USER)).thenReturn(Mono.empty());
        when(mediaStore.deletePrefix(AVATARS)).thenReturn(Mono.empty());
        when(jobs.markMediaDeleted(USER)).thenReturn(Mono.empty());

        StepVerifier.create(switchedOff().processDueDeletions(20))
                .expectNext(new DeletionProgress(USER, DeletionProgress.Status.COMPLETED, 0, null))
                .verifyComplete();
    }

    @Test
    void processDueDeletions_shouldStillPurgeMedia_whenTheIdentityStepFails() {
        givenTheJobs(job(NOW.minus(Duration.ofMinutes(11)), false, false, 0));
        when(identityProvider.deleteSubject("kc-1"))
                .thenReturn(Mono.error(new TechnicalException(TechnicalErrorCode.KEYCLOAK_ERROR)));
        when(mediaStore.deletePrefix(AVATARS)).thenReturn(Mono.empty());
        when(jobs.markMediaDeleted(USER)).thenReturn(Mono.empty());
        when(jobs.recordFailure(eq(USER), anyString(), any())).thenReturn(Mono.empty());

        StepVerifier.create(service.processDueDeletions(20))
                .expectNext(new DeletionProgress(USER, DeletionProgress.Status.FAILED, 1, "USR-TEC-001"))
                .verifyComplete();

        verify(mediaStore).deletePrefix(AVATARS);
        verify(jobs).markMediaDeleted(USER);
        verify(jobs, never()).markIdentityDeleted(any());
    }

    @Test
    void processDueDeletions_shouldRecordAFailureWithTheFirstBackoff_whenTheIdentityProviderIsDown() {
        givenTheJobs(job(NOW.minusSeconds(5), false, true, 0));
        when(identityProvider.deleteSubject("kc-1"))
                .thenReturn(Mono.error(new TechnicalException(TechnicalErrorCode.KEYCLOAK_ERROR)));
        when(jobs.recordFailure(eq(USER), anyString(), any())).thenReturn(Mono.empty());

        StepVerifier.create(service.processDueDeletions(20)).expectNextCount(1).verifyComplete();

        verify(jobs).recordFailure(USER, "USR-TEC-001", NOW.plusSeconds(5));
    }

    @Test
    void processDueDeletions_shouldRecordAFailure_whenTheMediaStoreIsDown() {
        givenTheJobs(job(NOW.minusSeconds(5), true, false, 0));
        when(mediaStore.deletePrefix(AVATARS))
                .thenReturn(Mono.error(new TechnicalException(TechnicalErrorCode.MEDIA_ERROR)));
        when(jobs.recordFailure(eq(USER), anyString(), any())).thenReturn(Mono.empty());

        StepVerifier.create(service.processDueDeletions(20))
                .expectNext(new DeletionProgress(USER, DeletionProgress.Status.FAILED, 1, "USR-TEC-004"))
                .verifyComplete();

        verify(jobs, never()).postpone(any(), any());
    }

    @Test
    void processDueDeletions_shouldDoubleTheBackoff_whenTheJobHasFailedBefore() {
        givenTheJobs(job(NOW.minusSeconds(5), false, true, 2));
        when(identityProvider.deleteSubject("kc-1"))
                .thenReturn(Mono.error(new TechnicalException(TechnicalErrorCode.KEYCLOAK_ERROR)));
        when(jobs.recordFailure(eq(USER), anyString(), any())).thenReturn(Mono.empty());

        StepVerifier.create(service.processDueDeletions(20)).expectNextCount(1).verifyComplete();

        verify(jobs).recordFailure(USER, "USR-TEC-001", NOW.plusSeconds(20));
    }

    @Test
    void processDueDeletions_shouldCapTheBackoffAtOneHour_whenFailuresKeepComing() {
        givenTheJobs(job(NOW.minusSeconds(5), false, true, 30));
        when(identityProvider.deleteSubject("kc-1"))
                .thenReturn(Mono.error(new TechnicalException(TechnicalErrorCode.KEYCLOAK_ERROR)));
        when(jobs.recordFailure(eq(USER), anyString(), any())).thenReturn(Mono.empty());

        StepVerifier.create(service.processDueDeletions(20)).expectNextCount(1).verifyComplete();

        verify(jobs).recordFailure(USER, "USR-TEC-001", NOW.plus(Duration.ofHours(1)));
    }

    @Test
    void processDueDeletions_shouldKeepRetrying_whenAttemptsExceedFive() {
        // Never parked: the sixth failure is scheduled like any other.
        givenTheJobs(job(NOW.minusSeconds(5), false, true, 5));
        when(identityProvider.deleteSubject("kc-1"))
                .thenReturn(Mono.error(new TechnicalException(TechnicalErrorCode.KEYCLOAK_ERROR)));
        when(jobs.recordFailure(eq(USER), anyString(), any())).thenReturn(Mono.empty());

        StepVerifier.create(service.processDueDeletions(20))
                .expectNext(new DeletionProgress(USER, DeletionProgress.Status.FAILED, 6, "USR-TEC-001"))
                .verifyComplete();

        verify(jobs).recordFailure(USER, "USR-TEC-001", NOW.plusSeconds(160));
    }

    @Test
    void processDueDeletions_shouldStoreAnErrorCodeOnly_whenAStepFails() {
        givenTheJobs(job(NOW.minusSeconds(5), false, true, 0));
        when(identityProvider.deleteSubject("kc-1"))
                .thenReturn(Mono.error(new IllegalStateException("https://keycloak/admin/users/kc-1 refused")));
        when(jobs.recordFailure(eq(USER), anyString(), any())).thenReturn(Mono.empty());

        StepVerifier.create(service.processDueDeletions(20)).expectNextCount(1).verifyComplete();

        ArgumentCaptor<String> stored = ArgumentCaptor.captor();
        verify(jobs).recordFailure(eq(USER), stored.capture(), any());
        assertThat(stored.getValue()).isEqualTo("IllegalStateException");
    }

    @Test
    void processDueDeletions_shouldGoOnWithTheNextJob_whenOneJobFails() {
        UUID other = UUID.randomUUID();
        AccountDeletionJob broken = job(NOW.minusSeconds(5), false, true, 0);
        AccountDeletionJob fine = new AccountDeletionJob(other, null, NOW.minus(Duration.ofHours(1)), true, false, 0);
        givenTheJobs(broken, fine);
        when(identityProvider.deleteSubject("kc-1"))
                .thenReturn(Mono.error(new TechnicalException(TechnicalErrorCode.KEYCLOAK_ERROR)));
        // Even recording the failure fails: the job is reported, and the next one still runs.
        when(jobs.recordFailure(eq(USER), anyString(), any()))
                .thenReturn(Mono.error(new TechnicalException(TechnicalErrorCode.DATABASE_ERROR)));
        when(mediaStore.deletePrefix("avatars/" + other + "/")).thenReturn(Mono.empty());
        when(jobs.markMediaDeleted(other)).thenReturn(Mono.empty());

        StepVerifier.create(service.processDueDeletions(20))
                .expectNext(new DeletionProgress(USER, DeletionProgress.Status.FAILED, 1, "USR-TEC-002"))
                .expectNext(new DeletionProgress(other, DeletionProgress.Status.COMPLETED, 0, null))
                .verifyComplete();
    }

    @Test
    void processDueDeletions_shouldReturnNothing_whenNoJobIsDue() {
        when(jobs.claimDue(20)).thenReturn(Flux.empty());

        StepVerifier.create(service.processDueDeletions(20)).verifyComplete();

        verifyNoInteractions(identityProvider, mediaStore);
    }
}
