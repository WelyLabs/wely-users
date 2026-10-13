package com.calendar.users.infrastructure.persistence.adapters;

import com.calendar.users.domain.ports.TransactionRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/** One R2DBC transaction around the work; the {@code @Transactional} adapters it calls join it. */
@Component
public class R2dbcTransactionRunnerAdapter implements TransactionRunner {

    private final TransactionalOperator transactionalOperator;

    public R2dbcTransactionRunnerAdapter(TransactionalOperator transactionalOperator) {
        this.transactionalOperator = transactionalOperator;
    }

    @Override
    public <T> Mono<T> inTransaction(Mono<T> work) {
        return transactionalOperator.transactional(work);
    }
}
