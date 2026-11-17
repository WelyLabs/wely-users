package com.calendar.users.domain.services;

import com.calendar.users.domain.models.AccountDeletionJob;
import com.calendar.users.domain.models.AccountDeletionPolicy;
import com.calendar.users.domain.models.AccountErasure;
import com.calendar.users.domain.models.AccountState;
import com.calendar.users.domain.models.DeletedUser;
import com.calendar.users.domain.models.DeletedUsersPage;
import com.calendar.users.domain.models.DeletionProgress;
import com.calendar.users.domain.models.UserPicture;
import com.calendar.users.domain.ports.AccountDeletionRepository;
import com.calendar.users.domain.ports.IdentityProvider;
import com.calendar.users.domain.ports.MediaStore;
import com.calendar.users.domain.ports.UserRepository;
import com.calendar.users.domain.ports.UsersActivity;
import com.calendar.users.exception.BusinessErrorCode;
import com.calendar.users.exception.BusinessException;
import com.calendar.users.exception.ErrorCodes;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Deletes accounts: the request only schedules the erasure, which the worker performs once the grace period
 * has run out, followed by the identity and the avatars, retried until done. Any sign-in before the due date
 * cancels the whole thing.
 */
public class AccountDeletionService {

    private final UserRepository users;
    private final AccountDeletionRepository jobs;
    private final IdentityProvider identityProvider;
    private final MediaStore mediaStore;
    private final AccountDeletionPolicy policy;
    private final Clock clock;
    private final UsersActivity activity;

    public AccountDeletionService(UserRepository users, AccountDeletionRepository jobs,
                                  IdentityProvider identityProvider, MediaStore mediaStore,
                                  AccountDeletionPolicy policy, Clock clock, UsersActivity activity) {
        this.users = users;
        this.jobs = jobs;
        this.identityProvider = identityProvider;
        this.mediaStore = mediaStore;
        this.policy = policy;
        this.clock = clock;
        this.activity = activity;
    }

    /** 403 while deletion is off, false for a protected account, 410 for a tombstone, 404 for an unknown id. */
    public Mono<Boolean> isDeletionAllowed(UUID userId) {
        if (!policy.enabled()) {
            return Mono.error(new BusinessException(BusinessErrorCode.ACCOUNT_DELETION_DISABLED));
        }
        if (policy.isProtected(userId)) {
            return Mono.just(false);
        }
        return users.findAccountState(userId)
                .switchIfEmpty(Mono.error(() -> new BusinessException(BusinessErrorCode.USER_NOT_FOUND)))
                .flatMap(state -> state == AccountState.DELETED
                        ? Mono.error(new BusinessException(BusinessErrorCode.USER_DELETED))
                        : Mono.just(true));
    }

    /**
     * Checks, in order, the switch, the demo guard, the tombstone (already deleted completes, whatever the sign-in
     * age), an already scheduled deletion (completes too), then the sign-in age, and schedules the erasure for the
     * end of the grace period. The worker does the rest once that date is reached.
     */
    public Mono<Void> requestDeletion(UUID userId, Instant authenticatedAt) {
        return Mono.defer(() -> {
            if (!policy.enabled()) {
                return Mono.error(new BusinessException(BusinessErrorCode.ACCOUNT_DELETION_DISABLED));
            }
            if (policy.isProtected(userId)) {
                return Mono.error(new BusinessException(BusinessErrorCode.ACCOUNT_PROTECTED));
            }
            return users.findAccountState(userId)
                    .defaultIfEmpty(AccountState.ACTIVE)
                    .flatMap(state -> state == AccountState.ACTIVE
                            ? scheduleIfRecentlyAuthenticated(userId, authenticatedAt)
                            : Mono.empty());
        });
    }

    private Mono<Void> scheduleIfRecentlyAuthenticated(UUID userId, Instant authenticatedAt) {
        Instant now = clock.instant();
        if (!policy.isRecentAuthentication(authenticatedAt, now)) {
            return Mono.error(new BusinessException(BusinessErrorCode.REAUTHENTICATION_REQUIRED));
        }
        Instant requestedAt = now.truncatedTo(ChronoUnit.MICROS);
        return users.scheduleDeletion(userId, requestedAt, requestedAt.plus(policy.gracePeriod()))
                .switchIfEmpty(Mono.error(() -> new BusinessException(BusinessErrorCode.USER_NOT_FOUND)))
                // False: a concurrent request scheduled it first and was counted.
                .flatMap(scheduled -> scheduled ? Mono.<Void>fromRunnable(activity::deletionRequested) : Mono.empty());
    }

    /** Cancels a scheduled deletion, whether or not deletion is still switched on; a no-op when none is pending. */
    public Mono<Void> cancelDeletion(UUID userId) {
        return users.cancelDeletion(userId).then();
    }

    /** When the caller's scheduled erasure is due; empty when nothing is scheduled. */
    public Mono<Instant> deletionDueAt(UUID userId) {
        return users.findDeletionDueAt(userId);
    }

    /** Those of these ids whose account was deleted. */
    public Mono<Set<UUID>> deletedAmong(Collection<UUID> userIds) {
        if (userIds.isEmpty()) {
            return Mono.just(Set.of());
        }
        return users.findDeletedIds(userIds).collect(Collectors.toUnmodifiableSet());
    }

    /** One page of tombstones after the cursor; next is the last item when more remain. */
    public Mono<DeletedUsersPage> deletedUsers(DeletedUser after, int limit) {
        return users.findDeleted(after, limit + 1)
                .collectList()
                .map(found -> {
                    if (found.size() <= limit) {
                        return new DeletedUsersPage(found, null);
                    }
                    List<DeletedUser> page = found.subList(0, limit);
                    return new DeletedUsersPage(page, page.getLast());
                });
    }

    /** Runs the identity and media steps of the due jobs, switched off or not; a failing job never stops the others. */
    public Flux<DeletionProgress> processDueDeletions(int limit) {
        return jobs.claimDue(limit).concatMap(this::advance);
    }

    private Mono<DeletionProgress> advance(AccountDeletionJob job) {
        return Mono.defer(() -> {
                    Instant now = clock.instant();
                    return erase(job, now)
                            .flatMap(erased -> erased.error() != null
                                    ? settle(job, now, erased)
                                    : deleteIdentity(job)
                                            .flatMap(identity -> purgeMedia(job, now)
                                                    .flatMap(media -> settle(job, now, identity, media))));
                })
                .doOnNext(progress -> {
                    if (progress.status() == DeletionProgress.Status.COMPLETED) {
                        activity.deletionCompleted();
                    }
                })
                .onErrorResume(error -> Mono.just(
                        new DeletionProgress(job.userId(), DeletionProgress.Status.FAILED, job.attempts() + 1,
                                ErrorCodes.of(error))));
    }

    /** First step of the job: the grace period is over, so the tombstone is written now. */
    private Mono<Step> erase(AccountDeletionJob job, Instant now) {
        return users.eraseAccount(job.userId(), now.truncatedTo(ChronoUnit.MICROS))
                .map(erasure -> erasure == AccountErasure.UNKNOWN_USER
                        ? Step.failed(new BusinessException(BusinessErrorCode.USER_NOT_FOUND))
                        : Step.DONE)
                .onErrorResume(error -> Mono.just(Step.failed(error)));
    }

    private Mono<Step> deleteIdentity(AccountDeletionJob job) {
        if (job.identityDeleted()) {
            return Mono.just(Step.DONE);
        }
        return identityProvider.deleteSubject(job.subjectId())
                .then(Mono.defer(() -> jobs.markIdentityDeleted(job.userId())))
                .thenReturn(Step.DONE)
                .onErrorResume(error -> Mono.just(Step.failed(error)));
    }

    /**
     * Purges on every pass; only a purge after the settle delay closes the step. With the grace period that
     * delay is always behind us, so a single purge, right after the tombstone of the same pass, closes it.
     */
    private Mono<Step> purgeMedia(AccountDeletionJob job, Instant now) {
        if (job.mediaDeleted()) {
            return Mono.just(Step.DONE);
        }
        Instant settledAt = policy.mediaSettledAt(job.requestedAt());
        return mediaStore.deletePrefix(UserPicture.STORED_PREFIX + job.userId() + "/")
                .then(Mono.defer(() -> now.isBefore(settledAt)
                        ? jobs.postpone(job.userId(), settledAt).thenReturn(Step.WAITING)
                        : jobs.markMediaDeleted(job.userId()).thenReturn(Step.DONE)))
                .onErrorResume(error -> Mono.just(Step.failed(error)));
    }

    private Mono<DeletionProgress> settle(AccountDeletionJob job, Instant now, Step... steps) {
        Throwable failure = Stream.of(steps).map(Step::error).filter(Objects::nonNull).findFirst().orElse(null);
        if (failure != null) {
            int attempts = job.attempts() + 1;
            String code = ErrorCodes.of(failure);
            return jobs.recordFailure(job.userId(), code, now.plus(policy.retryDelay(attempts)))
                    .thenReturn(new DeletionProgress(job.userId(), DeletionProgress.Status.FAILED, attempts, code));
        }
        DeletionProgress.Status status = Stream.of(steps).allMatch(Step::done)
                ? DeletionProgress.Status.COMPLETED
                : DeletionProgress.Status.WAITING;
        return Mono.just(new DeletionProgress(job.userId(), status, job.attempts(), null));
    }

    /** One step of a job on this pass; error is set when it failed. */
    private record Step(boolean done, Throwable error) {

        static final Step DONE = new Step(true, null);
        static final Step WAITING = new Step(false, null);

        static Step failed(Throwable error) {
            return new Step(false, error);
        }
    }
}
