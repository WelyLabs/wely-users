package com.calendar.users.configuration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;

/**
 * Transaction plumbing.
 *
 * <p>Spring Boot auto-configures a {@link ReactiveTransactionManager} as soon as an R2DBC
 * {@code ConnectionFactory} is on the context, but not a {@link TransactionalOperator} —
 * the programmatic form, which is what this service uses because the unit of work is
 * assembled by the domain rather than bounded by a method.
 *
 * <p>Declared here rather than built inside the adapter so that it can be substituted in a
 * test, and so that the one place transactions are configured is a file named after the
 * concern.
 */
@Configuration
public class PersistenceConfig {

    @Bean
    public TransactionalOperator transactionalOperator(ReactiveTransactionManager transactionManager) {
        return TransactionalOperator.create(transactionManager);
    }
}
