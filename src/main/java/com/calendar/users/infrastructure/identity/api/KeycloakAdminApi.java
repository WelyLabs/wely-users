package com.calendar.users.infrastructure.identity.api;


import com.calendar.users.infrastructure.identity.models.KeycloakUserResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import reactor.core.publisher.Mono;

@HttpExchange("/users")
public interface KeycloakAdminApi {

    @GetExchange("/{keycloakId}")
    Mono<KeycloakUserResponse> getUser(
            @PathVariable String keycloakId
    );

//    @PutExchange("/{userId}")
//    Mono<Void> updateAttributes(
//
//            @PathVariable String userId,
//            @RequestBody KeycloakUserUpdateRequest userUpdate
//    );
//
//    @PutExchange("/{userId}/execute-actions-email")
//    Mono<Void> sendUpdateEmailAction(
//
//            @PathVariable String userId,
//            @RequestBody String[] actions
//    );
}
