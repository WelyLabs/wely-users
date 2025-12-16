package com.calendar.users.infrastructure.api;


import com.calendar.users.infrastructure.models.dtos.KeycloakUserResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PutExchange;
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
