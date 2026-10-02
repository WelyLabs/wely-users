package com.calendar.users.infrastructure.persistence.adapters;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Checks the delegation. That the boundary is a real transaction, and that both writes
 * roll back together, is asserted against PostgreSQL in
 * {@link R2dbcTransactionBoundaryAdapterIntegrationTest} — a mock cannot say anything
 * about that.
 */
@ExtendWith(MockitoExtension.class)
class R2dbcTransactionBoundaryAdapterTest {

    @Mock
    private TransactionalOperator transactionalOperator;

    @Test
    @DisplayName("the operation is handed to the operator, not subscribed on the side")
    void atomically_shouldDelegateToTheOperator() {
        Mono<String> operation = Mono.just("written");
        when(transactionalOperator.transactional(any(Mono.class))).thenReturn(Mono.just("committed"));

        StepVerifier.create(new R2dbcTransactionBoundaryAdapter(transactionalOperator).atomically(operation))
                .expectNext("committed")
                .verifyComplete();

        // The exact publisher the caller assembled must reach the operator. Wrapping or
        // re-subscribing it elsewhere would run the writes twice, or outside the boundary.
        ArgumentCaptor<Mono<String>> handedOver = ArgumentCaptor.captor();
        org.mockito.Mockito.verify(transactionalOperator).transactional(handedOver.capture());
        assertThat(handedOver.getValue()).isSameAs(operation);
    }
}
