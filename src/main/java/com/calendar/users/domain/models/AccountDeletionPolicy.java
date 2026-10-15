package com.calendar.users.domain.models;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Account deletion rules: whether it is on, who may never delete, how fresh a sign-in must be, how failures back off. */
public record AccountDeletionPolicy(
        boolean enabled,
        Set<UUID> protectedUserIds,
        Duration maxAuthenticationAge,
        Duration allowedClockSkew,
        Duration initialRetryDelay,
        Duration maxRetryDelay,
        Duration mediaSettleDelay
) {

    // 2^62 is the largest power of two a long holds; anything past it is capped anyway.
    private static final int MAX_DOUBLINGS = 62;

    public AccountDeletionPolicy {
        protectedUserIds = Set.copyOf(Objects.requireNonNull(protectedUserIds, "protectedUserIds"));
        requirePositiveOrZero(maxAuthenticationAge, "maxAuthenticationAge");
        requirePositiveOrZero(allowedClockSkew, "allowedClockSkew");
        requirePositiveOrZero(initialRetryDelay, "initialRetryDelay");
        requirePositiveOrZero(maxRetryDelay, "maxRetryDelay");
        requirePositiveOrZero(mediaSettleDelay, "mediaSettleDelay");
    }

    public boolean isProtected(UUID userId) {
        return protectedUserIds.contains(userId);
    }

    /** False when the sign-in time is unknown, older than the max age, or further ahead than the clock skew allows. */
    public boolean isRecentAuthentication(Instant authenticatedAt, Instant now) {
        return authenticatedAt != null
                && !authenticatedAt.isBefore(now.minus(maxAuthenticationAge))
                && !authenticatedAt.isAfter(now.plus(allowedClockSkew));
    }

    /** The wait before the next attempt: the initial delay doubled per earlier failure, capped at the max delay. */
    public Duration retryDelay(int failedAttempts) {
        int doublings = Math.min(Math.max(failedAttempts - 1, 0), MAX_DOUBLINGS);
        try {
            Duration delay = initialRetryDelay.multipliedBy(1L << doublings);
            return delay.compareTo(maxRetryDelay) < 0 ? delay : maxRetryDelay;
        } catch (ArithmeticException overflow) {
            return maxRetryDelay;
        }
    }

    /** From then on, no avatar upload can still be racing the deletion. */
    public Instant mediaSettledAt(Instant requestedAt) {
        return requestedAt.plus(mediaSettleDelay);
    }

    private static void requirePositiveOrZero(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isNegative()) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
    }
}
