package com.calendar.users.infrastructure.persistence.adapters;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class R2dbcTransactionRunnerAdapterTest {

    @Mock
    private TransactionalOperator transactionalOperator;

    @Test
    void inTransaction_shouldHandTheWorkToTheTransactionalOperator() {
        Mono<String> work = Mono.just("done");
        when(transactionalOperator.transactional(work)).thenReturn(work);

        StepVerifier.create(new R2dbcTransactionRunnerAdapter(transactionalOperator).inTransaction(work))
                .expectNext("done")
                .verifyComplete();

        verify(transactionalOperator).transactional(work);
    }
}
