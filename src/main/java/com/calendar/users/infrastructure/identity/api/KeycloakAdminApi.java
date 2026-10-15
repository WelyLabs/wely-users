package com.calendar.users.infrastructure.identity.api;


import com.calendar.users.infrastructure.identity.models.KeycloakUserResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.DeleteExchange;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;
import org.springframework.web.service.annotation.PutExchange;
import reactor.core.publisher.Mono;

import java.util.Map;

/** The slice of Keycloak's Admin API this service calls; everything but reading needs {@code manage-users}. */
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

    /** Ends every session of the user and revokes the tokens issued before now. */
    @PostExchange("/{keycloakId}/logout")
    Mono<Void> logout(@PathVariable String keycloakId);

    @DeleteExchange("/{keycloakId}")
    Mono<Void> deleteUser(@PathVariable String keycloakId);
}
