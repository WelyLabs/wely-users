package com.calendar.users.domain.ports;

import reactor.core.publisher.Mono;

/**
 * Runs work in one database transaction: committed if it completes, rolled back if it fails.
 *
 * <p>A port rather than {@code @Transactional}: the domain services are plain objects, which
 * Spring's proxies do not reach.
 */
public interface TransactionRunner {

    <T> Mono<T> inTransaction(Mono<T> work);
}
