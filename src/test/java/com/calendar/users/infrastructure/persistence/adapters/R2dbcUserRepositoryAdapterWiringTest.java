package com.calendar.users.infrastructure.persistence.adapters;

import com.calendar.users.domain.models.BusinessUser;
import com.calendar.users.domain.ports.AccountDeletionRepository;
import com.calendar.users.domain.ports.UserRepository;
import com.calendar.users.infrastructure.deletion.AccountDeletionWorker;
import com.calendar.users.infrastructure.identity.reaper.OrphanUserReaper;
import com.calendar.users.infrastructure.messaging.relay.OutboxRelay;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Checks, in a real Spring context, how the user adapter and the outbox are wired. */
@SpringBootTest
@ActiveProfiles("test")
class R2dbcUserRepositoryAdapterWiringTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private R2dbcOutboxEventStoreAdapter outboxEventStore;

    @Autowired
    private AccountDeletionRepository accountDeletionRepository;

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("the user adapter is proxied and its save is transactional")
    void save_shouldRunInATransaction() throws NoSuchMethodException {
        assertThat(AopUtils.isAopProxy(userRepository)).isTrue();
        assertThat(R2dbcUserRepositoryAdapter.class
                .getMethod("save", BusinessUser.class, String.class)
                .isAnnotationPresent(Transactional.class))
                .isTrue();
    }

    @Test
    @DisplayName("the account erasure is transactional: tombstone, handle, job and events commit together")
    void eraseAccount_shouldRunInATransaction() throws NoSuchMethodException {
        assertThat(R2dbcUserRepositoryAdapter.class
                .getMethod("eraseAccount", UUID.class, Instant.class)
                .isAnnotationPresent(Transactional.class))
                .isTrue();
        assertThat(R2dbcUserRepositoryAdapter.class
                .getMethod("deleteOrphan", UUID.class)
                .isAnnotationPresent(Transactional.class))
                .isTrue();
    }

    @Test
    @DisplayName("the deletion job adapter is advised, so a failed query surfaces as a database error")
    void accountDeletionAdapter_shouldBeAdvisedByTheAspect() {
        assertThat(AopUtils.isAopProxy(accountDeletionRepository)).isTrue();
    }

    @Test
    @DisplayName("the account deletion worker does not start when it is switched off")
    void accountDeletionWorker_shouldBeAbsent_whenDisabled() {
        // app.account-deletion.worker.enabled=false in application-test.properties.
        assertThat(context.getBeanNamesForType(AccountDeletionWorker.class)).isEmpty();
    }

    @Test
    @DisplayName("the outbox store is advised, so a failed insert surfaces as a database error")
    void outboxEventStore_shouldBeAdvisedByTheAspect() {
        assertThat(AopUtils.isAopProxy(outboxEventStore)).isTrue();
    }

    @Test
    @DisplayName("the relay does not start when it is switched off")
    void outboxRelay_shouldBeAbsentWhenDisabled() {
        // app.outbox.relay.enabled=false in application-test.properties.
        assertThat(context.getBeanNamesForType(OutboxRelay.class)).isEmpty();
    }

    @Test
    @DisplayName("the orphan reaper does not start when it is switched off")
    void orphanUserReaper_shouldBeAbsentWhenDisabled() {
        // app.orphan-reaper.enabled=false in application-test.properties.
        assertThat(context.getBeanNamesForType(OrphanUserReaper.class)).isEmpty();
    }
}
