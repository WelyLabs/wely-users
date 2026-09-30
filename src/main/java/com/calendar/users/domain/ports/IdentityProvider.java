package com.calendar.users.domain.ports;

import com.calendar.users.domain.models.IdentityUser;
import reactor.core.publisher.Mono;

/**
 * Reads a user from whatever system owns authentication.
 *
 * <p>Nothing here names Keycloak: the domain declares what it needs, and
 * {@code infrastructure.identity} adapts. An empty result means the provider has no such
 * subject.
 */
public interface IdentityProvider {

    Mono<IdentityUser> getUser(String subjectId);
}
