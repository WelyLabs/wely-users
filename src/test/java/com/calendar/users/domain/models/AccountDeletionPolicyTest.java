package com.calendar.users.domain.models;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountDeletionPolicyTest {

    private static final UUID DEMO = UUID.fromString("89a380b8-f66a-44d0-b9d9-616241080430");
    private static final Instant NOW = Instant.parse("2026-10-15T14:00:00Z");

    private static AccountDeletionPolicy policy(Set<UUID> protectedIds) {
        return new AccountDeletionPolicy(true, protectedIds, Duration.ofMinutes(5), Duration.ofSeconds(60),
                Duration.ofSeconds(5), Duration.ofHours(1), Duration.ofMinutes(10));
    }

    private final AccountDeletionPolicy policy = policy(Set.of(DEMO));

    @Test
    void isProtected_shouldAnswerTrue_whenTheIdIsListed() {
        assertThat(policy.isProtected(DEMO)).isTrue();
        assertThat(policy.isProtected(UUID.randomUUID())).isFalse();
    }

    @Test
    void isProtected_shouldAnswerFalse_whenTheListIsEmpty() {
        assertThat(policy(Set.of()).isProtected(DEMO)).isFalse();
    }

    @Test
    void isProtected_shouldStillAnswerTrue_whenDeletionIsDisabled() {
        AccountDeletionPolicy disabled = new AccountDeletionPolicy(false, Set.of(DEMO), Duration.ofMinutes(5),
                Duration.ofSeconds(60), Duration.ofSeconds(5), Duration.ofHours(1), Duration.ofMinutes(10));

        assertThat(disabled.enabled()).isFalse();
        assertThat(disabled.isProtected(DEMO)).isTrue();
    }

    @Test
    void isRecentAuthentication_shouldAnswerFalse_whenAuthTimeIsNull() {
        assertThat(policy.isRecentAuthentication(null, NOW)).isFalse();
    }

    @Test
    void isRecentAuthentication_shouldAnswerFalse_whenAuthTimeIsOlderThanTheMaxAge() {
        assertThat(policy.isRecentAuthentication(NOW.minusSeconds(301), NOW)).isFalse();
    }

    @Test
    void isRecentAuthentication_shouldAnswerTrue_whenAuthTimeIsExactlyTheMaxAge() {
        assertThat(policy.isRecentAuthentication(NOW.minusSeconds(300), NOW)).isTrue();
    }

    @Test
    void isRecentAuthentication_shouldAnswerFalse_whenAuthTimeIsBeyondTheSkew() {
        assertThat(policy.isRecentAuthentication(NOW.plusSeconds(61), NOW)).isFalse();
    }

    @Test
    void isRecentAuthentication_shouldAnswerTrue_whenAuthTimeIsWithinTheSkew() {
        assertThat(policy.isRecentAuthentication(NOW.plusSeconds(60), NOW)).isTrue();
        assertThat(policy.isRecentAuthentication(NOW, NOW)).isTrue();
    }

    @Test
    void retryDelay_shouldBeTheInitialDelay_whenItIsTheFirstFailure() {
        assertThat(policy.retryDelay(1)).isEqualTo(Duration.ofSeconds(5));
        assertThat(policy.retryDelay(0)).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void retryDelay_shouldDouble_whenFailuresAccumulate() {
        assertThat(policy.retryDelay(2)).isEqualTo(Duration.ofSeconds(10));
        assertThat(policy.retryDelay(3)).isEqualTo(Duration.ofSeconds(20));
        assertThat(policy.retryDelay(10)).isEqualTo(Duration.ofSeconds(5 * 512));
    }

    @Test
    void retryDelay_shouldCapAtTheMaxDelay_whenFailuresAreMany() {
        assertThat(policy.retryDelay(11)).isEqualTo(Duration.ofHours(1));
        assertThat(policy.retryDelay(40)).isEqualTo(Duration.ofHours(1));
    }

    @Test
    void retryDelay_shouldNotOverflow_whenAttemptsAreVeryHigh() {
        assertThat(policy.retryDelay(Integer.MAX_VALUE)).isEqualTo(Duration.ofHours(1));
    }

    @Test
    void mediaSettledAt_shouldAddTheSettleDelay_whenGivenTheRequestTime() {
        assertThat(policy.mediaSettledAt(NOW)).isEqualTo(NOW.plus(Duration.ofMinutes(10)));
    }

    @Test
    void constructor_shouldCopyTheProtectedIds_whenTheCallerMutatesItsSet() {
        Set<UUID> ids = new HashSet<>(Set.of(DEMO));
        AccountDeletionPolicy copied = policy(ids);

        ids.clear();

        assertThat(copied.isProtected(DEMO)).isTrue();
        assertThatThrownBy(() -> copied.protectedUserIds().add(UUID.randomUUID()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void constructor_shouldRefuse_whenADurationIsNegative() {
        assertThatThrownBy(() -> new AccountDeletionPolicy(true, Set.of(), Duration.ofMinutes(-1), Duration.ZERO,
                Duration.ZERO, Duration.ZERO, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructor_shouldRefuse_whenAValueIsMissing() {
        assertThatThrownBy(() -> new AccountDeletionPolicy(true, null, Duration.ZERO, Duration.ZERO,
                Duration.ZERO, Duration.ZERO, Duration.ZERO))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new AccountDeletionPolicy(true, Set.of(), Duration.ZERO, Duration.ZERO,
                null, Duration.ZERO, Duration.ZERO))
                .isInstanceOf(NullPointerException.class);
    }
}
