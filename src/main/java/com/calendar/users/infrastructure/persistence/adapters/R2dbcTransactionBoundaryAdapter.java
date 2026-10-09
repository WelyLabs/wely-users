package com.calendar.users.infrastructure.persistence.adapters;

import com.calendar.users.domain.ports.TransactionBoundary;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/**
 * Runs the domain's operation in one PostgreSQL transaction.
 * In a reactive chain the transaction travels in the Reactor context, not in a thread-local.
 */
@Component
public class R2dbcTransactionBoundaryAdapter implements TransactionBoundary {

    private final TransactionalOperator transactionalOperator;

    public R2dbcTransactionBoundaryAdapter(TransactionalOperator transactionalOperator) {
        this.transactionalOperator = transactionalOperator;
    }

    @Override
    public <T> Mono<T> atomically(Mono<T> operation) {
        return transactionalOperator.transactional(operation);
    }
}
