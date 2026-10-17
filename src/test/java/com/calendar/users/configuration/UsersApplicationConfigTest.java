package com.calendar.users.configuration;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.calendar.users.domain.models.AccountDeletionPolicy;
import com.calendar.users.domain.ports.AccountDeletionRepository;
import com.calendar.users.domain.ports.FriendshipChecker;
import com.calendar.users.domain.ports.IdentityProvider;
import com.calendar.users.domain.ports.MediaStore;
import com.calendar.users.domain.ports.UserRepository;
import com.calendar.users.domain.services.AccountDeletionService;
import com.calendar.users.domain.services.UserService;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class UsersApplicationConfigTest {

    private static final UUID FIRST = UUID.fromString("89a380b8-f66a-44d0-b9d9-616241080430");
    private static final UUID SECOND = UUID.fromString("00000000-0000-4000-8000-000000000001");

    private final UsersApplicationConfig config = new UsersApplicationConfig();

    @Test
    void parseProtectedUserIds_shouldReturnAnEmptySet_whenThePropertyIsBlank() {
        assertThat(UsersApplicationConfig.parseProtectedUserIds("")).isEmpty();
        assertThat(UsersApplicationConfig.parseProtectedUserIds("  ")).isEmpty();
        assertThat(UsersApplicationConfig.parseProtectedUserIds(null)).isEmpty();
    }

    @Test
    void parseProtectedUserIds_shouldTrimAndSkipEmptyEntries_whenTheListHasSpaces() {
        assertThat(UsersApplicationConfig.parseProtectedUserIds(" " + FIRST + " ,, " + SECOND + ",\n"))
                .containsExactlyInAnyOrder(FIRST, SECOND);
    }

    @Test
    void parseProtectedUserIds_shouldFail_whenAnEntryIsNotAUuid() {
        assertThatThrownBy(() -> UsersApplicationConfig.parseProtectedUserIds(FIRST + ",camille"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parseProtectedUserIds_shouldReturnAnImmutableSet_whenParsed() {
        Set<UUID> parsed = UsersApplicationConfig.parseProtectedUserIds(FIRST.toString());

        assertThatThrownBy(() -> parsed.add(SECOND)).isInstanceOf(UnsupportedOperationException.class);
    }

    private AccountDeletionPolicy policy(boolean enabled, String protectedUserIds) {
        return config.accountDeletionPolicy(enabled, protectedUserIds, Duration.ofMinutes(5),
                Duration.ofSeconds(60), Duration.ofSeconds(5), Duration.ofHours(1), Duration.ofMinutes(10));
    }

    /** The messages UsersApplicationConfig logs at INFO while the action runs. */
    private static List<String> infoLogsOf(Runnable action) {
        Logger logger = (Logger) LoggerFactory.getLogger(UsersApplicationConfig.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        Level previous = logger.getLevel();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.INFO);
        try {
            action.run();
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previous);
        }
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    @Test
    void accountDeletionPolicy_shouldCarryTheSwitchAndTheConfiguredDurations_whenBuilt() {
        assertThat(policy(true, FIRST.toString())).isEqualTo(new AccountDeletionPolicy(true, Set.of(FIRST),
                Duration.ofMinutes(5), Duration.ofSeconds(60), Duration.ofSeconds(5), Duration.ofHours(1),
                Duration.ofMinutes(10)));
    }

    @Test
    void accountDeletionPolicy_shouldBeDisabledAndKeepTheProtectedIds_whenTheSwitchIsOff() {
        AccountDeletionPolicy policy = policy(false, FIRST + "," + SECOND);

        assertThat(policy.enabled()).isFalse();
        assertThat(policy.protectedUserIds()).containsExactlyInAnyOrder(FIRST, SECOND);
    }

    @Test
    void accountDeletionPolicy_shouldLogTheProtectedCountAndTheSwitch_whenBuilt() {
        assertThat(infoLogsOf(() -> policy(true, FIRST + "," + SECOND)))
                .containsExactly("Account deletion: 2 protected account(s), enabled");
        assertThat(infoLogsOf(() -> policy(false, "")))
                .containsExactly("Account deletion: 0 protected account(s), disabled");
    }

    @Test
    void accountDeletionPolicy_shouldFailTheStartup_whenAProtectedIdIsInvalid() {
        assertThatThrownBy(() -> policy(true, "camille")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void accountDeletionService_shouldBeAPlainObjectBuiltFromThePorts_whenWired() {
        AccountDeletionService service = config.accountDeletionService(mock(UserRepository.class),
                mock(AccountDeletionRepository.class), mock(IdentityProvider.class), mock(MediaStore.class),
                config.accountDeletionPolicy(true, "", Duration.ZERO, Duration.ZERO, Duration.ZERO,
                        Duration.ZERO, Duration.ZERO),
                Clock.systemUTC());

        assertThat(service).isNotNull();
        assertThat(AccountDeletionService.class.getAnnotations()).isEmpty();
    }

    @Test
    void userService_shouldBeAPlainObjectBuiltFromThePorts_whenWired() {
        UserService service = config.userService(mock(UserRepository.class), mock(IdentityProvider.class),
                mock(MediaStore.class), mock(FriendshipChecker.class), Clock.systemUTC());

        assertThat(service).isNotNull();
        assertThat(UserService.class.getAnnotations()).isEmpty();
    }

    @Test
    void clock_shouldBeUtc_whenProvided() {
        assertThat(config.clock().getZone()).isEqualTo(ZoneOffset.UTC);
    }
}
