package com.calendar.users.infrastructure.identity.api;


import com.calendar.users.infrastructure.identity.models.KeycloakUserResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PutExchange;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * The slice of Keycloak's Admin API this service calls: reading a user, and writing back the
 * fields the profile page edits. Writing needs the client's {@code manage-users} role.
 */
@HttpExchange("/users")
public interface KeycloakAdminApi {

    @GetExchange("/{keycloakId}")
    Mono<KeycloakUserResponse> getUser(
            @PathVariable String keycloakId
    );

    /**
     * The whole user representation, untyped on purpose: an update writes it back, and a typed
     * record would drop every field it does not declare.
     */
    @GetExchange("/{keycloakId}")
    Mono<Map<String, Object>> getUserRepresentation(@PathVariable String keycloakId);

    @PutExchange("/{keycloakId}")
    Mono<Void> updateUser(@PathVariable String keycloakId, @RequestBody Map<String, Object> representation);
}
