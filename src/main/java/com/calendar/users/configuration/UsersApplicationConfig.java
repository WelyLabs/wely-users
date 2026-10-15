package com.calendar.users.configuration;

import com.calendar.users.domain.models.AccountDeletionPolicy;
import com.calendar.users.domain.ports.AccountDeletionRepository;
import com.calendar.users.domain.ports.FriendshipChecker;
import com.calendar.users.domain.ports.IdentityProvider;
import com.calendar.users.domain.ports.MediaStore;
import com.calendar.users.domain.ports.UserRepository;
import com.calendar.users.domain.services.AccountDeletionService;
import com.calendar.users.domain.services.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Configuration
public class UsersApplicationConfig {

    @Bean
    public UserService userService(UserRepository userRepository, IdentityProvider identityProvider,
                                   MediaStore mediaStore, FriendshipChecker friendshipChecker) {
        return new UserService(userRepository, identityProvider, mediaStore, friendshipChecker);
    }

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public AccountDeletionPolicy accountDeletionPolicy(
            @Value("${app.account-deletion.enabled:false}") boolean enabled,
            @Value("${app.account-deletion.protected-user-ids:}") String protectedUserIds,
            @Value("${app.account-deletion.max-auth-age:5m}") Duration maxAuthenticationAge,
            @Value("${app.account-deletion.allowed-clock-skew:60s}") Duration allowedClockSkew,
            @Value("${app.account-deletion.retry.initial-delay:5s}") Duration initialRetryDelay,
            @Value("${app.account-deletion.retry.max-delay:1h}") Duration maxRetryDelay,
            @Value("${app.account-deletion.media-settle-delay:10m}") Duration mediaSettleDelay) {
        Set<UUID> protectedIds = parseProtectedUserIds(protectedUserIds);
        log.info("Account deletion: {} protected account(s), {}", protectedIds.size(), enabled ? "enabled" : "disabled");
        return new AccountDeletionPolicy(enabled, protectedIds, maxAuthenticationAge, allowedClockSkew,
                initialRetryDelay, maxRetryDelay, mediaSettleDelay);
    }

    @Bean
    public AccountDeletionService accountDeletionService(UserRepository userRepository,
                                                         AccountDeletionRepository accountDeletionRepository,
                                                         IdentityProvider identityProvider, MediaStore mediaStore,
                                                         AccountDeletionPolicy accountDeletionPolicy, Clock clock) {
        return new AccountDeletionService(userRepository, accountDeletionRepository, identityProvider, mediaStore,
                accountDeletionPolicy, clock);
    }

    /** Comma- or space-separated UUIDs; an invalid entry fails the startup rather than leaving an account unprotected. */
    static Set<UUID> parseProtectedUserIds(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(raw.split("[,\\s]+"))
                .map(String::trim)
                .filter(entry -> !entry.isEmpty())
                .map(UUID::fromString)
                .collect(Collectors.toUnmodifiableSet());
    }
}
