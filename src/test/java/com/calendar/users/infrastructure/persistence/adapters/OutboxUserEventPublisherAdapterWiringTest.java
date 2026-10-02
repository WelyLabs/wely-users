package com.calendar.users.infrastructure.persistence.adapters;

import com.calendar.users.domain.ports.TransactionBoundary;
import com.calendar.users.domain.ports.UserEventPublisher;
import com.calendar.users.infrastructure.messaging.relay.OutboxRelay;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the swap actually took effect in the real context.
 *
 * <p>The domain port did not change, which is the point — and also the risk: a second bean
 * implementing {@code UserEventPublisher}, or a leftover Kafka one winning the injection,
 * would compile, start, and quietly put the broker back on the request path. Only a wired
 * context can tell.
 */
@SpringBootTest
@ActiveProfiles("test")
class OutboxUserEventPublisherAdapterWiringTest {

    @Autowired
    private UserEventPublisher userEventPublisher;

    @Autowired
    private TransactionBoundary transactionBoundary;

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("the domain's publisher is the outbox, and it is the only one")
    void userEventPublisher_shouldBeTheOutboxAdapter() {
        assertThat(AopUtils.getTargetClass(userEventPublisher))
                .isEqualTo(OutboxUserEventPublisherAdapter.class);

        assertThat(context.getBeanNamesForType(UserEventPublisher.class))
                .as("a second publisher would make the injection order decide whether events "
                        + "go through the outbox")
                .hasSize(1);
    }

    @Test
    @DisplayName("the publisher is advised, so a failed insert surfaces as a database error")
    void userEventPublisher_shouldBeAdvisedByTheAspect() {
        // This is why the adapter sits in persistence.adapters rather than in messaging:
        // the pointcut keys on the package, and a failed outbox insert is a PostgreSQL
        // failure, not a broker one.
        assertThat(AopUtils.isAopProxy(userEventPublisher)).isTrue();
        assertThat(AopUtils.isAopProxy(transactionBoundary)).isTrue();
    }

    @Test
    @DisplayName("the relay does not start when it is switched off")
    void outboxRelay_shouldBeAbsentWhenDisabled() {
        // app.outbox.relay.enabled=false in application-test.properties. Without it the
        // relay would start with every @SpringBootTest and poll a database that is not
        // there, once every two seconds, for the length of the build.
        assertThat(context.getBeanNamesForType(OutboxRelay.class)).isEmpty();
    }
}
