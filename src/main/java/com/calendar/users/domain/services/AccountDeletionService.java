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
import com.calendar.users.exception.BusinessErrorCode;
import com.calendar.users.exception.BusinessException;
import com.calendar.users.exception.TechnicalException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Deletes accounts: the tombstone at once, the identity and the avatars afterwards, retried until done. */
public class AccountDeletionService {

    private final UserRepository users;
    private final AccountDeletionRepository jobs;
    private final IdentityProvider identityProvider;
    private final MediaStore mediaStore;
    private final AccountDeletionPolicy policy;
    private final Clock clock;

    public AccountDeletionService(UserRepository users, AccountDeletionRepository jobs,
                                  IdentityProvider identityProvider, MediaStore mediaStore,
                                  AccountDeletionPolicy policy, Clock clock) {
        this.users = users;
        this.jobs = jobs;
        this.identityProvider = identityProvider;
        this.mediaStore = mediaStore;
        this.policy = policy;
        this.clock = clock;
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
                .flatMap(state -> state == AccountState.ACTIVE
                        ? Mono.just(true)
                        : Mono.error(new BusinessException(BusinessErrorCode.USER_DELETED)));
    }

    /**
     * Checks, in order, the switch, the demo guard, the tombstone (already deleted completes, whatever the sign-in
     * age), then the sign-in age, and erases the account; Keycloak and the avatars are left to the worker.
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
                    .map(state -> state == AccountState.DELETED)
                    .defaultIfEmpty(false)
                    .flatMap(deleted -> deleted ? Mono.empty() : eraseIfRecentlyAuthenticated(userId, authenticatedAt));
        });
    }

    private Mono<Void> eraseIfRecentlyAuthenticated(UUID userId, Instant authenticatedAt) {
        Instant now = clock.instant();
        if (!policy.isRecentAuthentication(authenticatedAt, now)) {
            return Mono.error(new BusinessException(BusinessErrorCode.REAUTHENTICATION_REQUIRED));
        }
        return users.eraseAccount(userId, now.truncatedTo(ChronoUnit.MICROS))
                .flatMap(erasure -> erasure == AccountErasure.UNKNOWN_USER
                        ? Mono.<Void>error(new BusinessException(BusinessErrorCode.USER_NOT_FOUND))
                        : Mono.<Void>empty());
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
                    return deleteIdentity(job)
                            .flatMap(identity -> purgeMedia(job, now)
                                    .flatMap(media -> settle(job, now, identity, media)));
                })
                .onErrorResume(error -> Mono.just(
                        new DeletionProgress(job.userId(), DeletionProgress.Status.FAILED, job.attempts() + 1,
                                errorCode(error))));
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

    /** Purges on every pass; only a purge after the settle delay closes the step. */
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

    private Mono<DeletionProgress> settle(AccountDeletionJob job, Instant now, Step identity, Step media) {
        Throwable failure = identity.error() != null ? identity.error() : media.error();
        if (failure != null) {
            int attempts = job.attempts() + 1;
            String code = errorCode(failure);
            return jobs.recordFailure(job.userId(), code, now.plus(policy.retryDelay(attempts)))
                    .thenReturn(new DeletionProgress(job.userId(), DeletionProgress.Status.FAILED, attempts, code));
        }
        DeletionProgress.Status status = identity.done() && media.done()
                ? DeletionProgress.Status.COMPLETED
                : DeletionProgress.Status.WAITING;
        return Mono.just(new DeletionProgress(job.userId(), status, job.attempts(), null));
    }

    /** A stable code to store and log: never a message, a URL or an id. */
    private static String errorCode(Throwable error) {
        if (error instanceof BusinessException business) {
            return business.getErrorCode().getCode();
        }
        if (error instanceof TechnicalException technical) {
            return technical.getErrorCode().getCode();
        }
        return error.getClass().getSimpleName();
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
