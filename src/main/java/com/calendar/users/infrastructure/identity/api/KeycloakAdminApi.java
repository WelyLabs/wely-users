package com.calendar.users.infrastructure.identity.api;


import com.calendar.users.infrastructure.identity.models.KeycloakUserResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import reactor.core.publisher.Mono;

/**
 * The slice of Keycloak's Admin API this service calls.
 *
 * <p>One method, because that is all the JIT provisioning flow needs: resolve a subject into a
 * user. Writing attributes back is Keycloak's own job, through its console or its token mappers.
 */
@HttpExchange("/users")
public interface KeycloakAdminApi {

    @GetExchange("/{keycloakId}")
    Mono<KeycloakUserResponse> getUser(
            @PathVariable String keycloakId
    );
}
