package com.calendar.users.domain.ports;

import reactor.core.publisher.Mono;

/**
 * Runs a sequence of writes as one unit, so either all of them are visible or none is.
 *
 * <p>The domain needs a way to say "these two writes belong together" — provisioning a
 * user and recording that it happened — without naming a transaction manager, a
 * connection, or a database. That is what this port is: the statement of the
 * requirement, with the mechanism left to infrastructure.
 *
 * <p>Reactor only, like the rest of the domain. The adapter is
 * {@code R2dbcTransactionBoundaryAdapter}, and the boundary it opens travels through the
 * subscriber context, which is why every repository call made inside {@code operation}
 * joins it without being told to.
 *
 * <p>Only writes belong inside. An HTTP call to the identity provider placed in here
 * would hold a database connection open for the length of a network round trip.
 */
public interface TransactionBoundary {

    <T> Mono<T> atomically(Mono<T> operation);
}
