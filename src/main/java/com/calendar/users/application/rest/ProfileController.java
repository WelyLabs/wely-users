package com.calendar.users.application.rest;

import com.calendar.users.domain.models.BusinessUser;
import com.calendar.users.domain.services.UserService;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.UUID;

@RestController
@RequestMapping("profile")
@Validated
public class ProfileController {

    private final UserService userService;

    public ProfileController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("resolve/{keycloakId}")
    public Mono<String> resolveInternalUserId(
            @NotNull @PathVariable String keycloakId) {
        return userService.resolveInternalUserId(keycloakId).map(String::valueOf);
    }

    @GetMapping
    public Mono<ResponseEntity<BusinessUser>> readProfile(
            @AuthenticationPrincipal Jwt jwt) {
        UUID userId = UUID.fromString(jwt.getClaimAsString("businessId"));

        return userService.readProfile(userId).map(ResponseEntity::ok);
    }

    // @PutMapping
    // public Mono<ResponseEntity> updateProfile(@RequestBody BusinessUser
    // businessUser) {
    //
    // }

}
