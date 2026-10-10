package com.calendar.users.infrastructure.persistence.adapters;

import com.calendar.users.domain.models.BusinessUser;
import com.calendar.users.domain.ports.UserRepository;
import com.calendar.users.infrastructure.messaging.relay.OutboxRelay;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

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
}
