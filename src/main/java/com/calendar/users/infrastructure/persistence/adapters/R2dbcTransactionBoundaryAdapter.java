package com.calendar.users.infrastructure.persistence.adapters;

import com.calendar.users.domain.ports.TransactionBoundary;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/**
 * Opens a real PostgreSQL transaction around the operation the domain handed over.
 *
 * <h2>Why not {@code @Transactional}</h2>
 *
 * <p>Two reasons, and the second is the one that matters.
 *
 * <p>The annotation lives in {@code org.springframework.transaction}, and the domain
 * forbids Spring imports. That alone could be worked around by annotating something in
 * this layer.
 *
 * <p>The real reason is that {@code @Transactional} is a proxy on a method boundary, and
 * the unit of work here is not a method — it is a sequence the domain assembles. Taking
 * the operation as an argument puts the boundary exactly where the domain drew it, and
 * makes it visible in {@code UserService} instead of hiding it in an annotation on a class
 * nobody reads. {@code wely-chat} is the counter-example: it carries {@code @Transactional}
 * annotations that do nothing at all, because no reactive transaction manager is declared,
 * and nothing says so.
 *
 * <h2>How the boundary reaches the queries</h2>
 *
 * <p>Not through a thread-local — there is no stable thread in a reactive chain.
 * {@link TransactionalOperator} puts the connection in the Reactor subscriber context, and
 * every R2DBC call subscribed inside the operation finds it there. That is why
 * {@code userRepository.save} and the outbox insert end up in one transaction without
 * either of them being passed anything.
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
