package com.calendar.users.infrastructure.identity.api;

import com.calendar.users.infrastructure.identity.models.Auth0UserResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.DeleteExchange;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PatchExchange;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * The slice of Auth0's Management API this service calls, with the scopes each needs:
 * read:users, update:users, delete:sessions, delete:users. The id ({@code auth0|...}) is a path
 * variable, so the URI template encodes its {@code |}.
 */
@HttpExchange("/users")
public interface Auth0ManagementApi {

    @GetExchange("/{userId}")
    Mono<Auth0UserResponse> getUser(@PathVariable String userId);

    /** A partial update: Auth0 leaves the fields the body does not name as they are. */
    @PatchExchange("/{userId}")
    Mono<Void> updateUser(@PathVariable String userId, @RequestBody Map<String, Object> changes);

    /** Ends every session of the user, and with them the refresh tokens bound to them. */
    @DeleteExchange("/{userId}/sessions")
    Mono<Void> deleteSessions(@PathVariable String userId);

    @DeleteExchange("/{userId}")
    Mono<Void> deleteUser(@PathVariable String userId);
}
