package com.calendar.users.configuration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;

/**
 * Programmatic transactions, for code that {@code @Transactional} does not reach: a private
 * method or a call from the class itself, such as {@code OutboxRelay}'s pass.
 * Spring Boot provides the {@link ReactiveTransactionManager}, not the {@link TransactionalOperator}.
 */
@Configuration
public class PersistenceConfig {

    @Bean
    public TransactionalOperator transactionalOperator(ReactiveTransactionManager transactionManager) {
        return TransactionalOperator.create(transactionManager);
    }
}
