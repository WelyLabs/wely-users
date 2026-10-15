package com.calendar.users.domain.ports;

import com.calendar.users.domain.models.IdentityUser;
import reactor.core.publisher.Mono;

/**
 * Reads and updates a user in whatever system owns authentication.
 *
 * <p>Nothing here names Keycloak: the domain declares what it needs, and
 * {@code infrastructure.identity} adapts. An empty result means the provider has no such
 * subject.
 */
public interface IdentityProvider {

    Mono<IdentityUser> getUser(String subjectId);

    /**
     * Changes the subject's username and name, leaving everything else as it is. Fails with
     * USERNAME_TAKEN if another subject has the username, PROFILE_INVALID if the provider refuses
     * the values.
     */
    Mono<Void> updateProfile(String subjectId, IdentityUser profile);

    /** Ends every session of the subject and removes it; a subject the provider does not know is not an error. */
    Mono<Void> deleteSubject(String subjectId);
}
