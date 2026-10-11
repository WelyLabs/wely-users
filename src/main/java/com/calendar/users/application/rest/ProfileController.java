package com.calendar.users.application.rest;

import com.calendar.users.application.dtos.ProvisionUserRequest;
import com.calendar.users.application.mappers.ProvisionUserRequestMapper;
import com.calendar.users.domain.models.BusinessUser;
import com.calendar.users.domain.services.UserService;
import jakarta.validation.Valid;
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
    private final ProvisionUserRequestMapper provisionUserRequestMapper;

    public ProfileController(UserService userService, ProvisionUserRequestMapper provisionUserRequestMapper) {
        this.userService = userService;
        this.provisionUserRequestMapper = provisionUserRequestMapper;
    }

    /** Called by Keycloak during registration. Idempotent: a known Keycloak id returns its user. */
    @PostMapping("provision")
    public Mono<String> provisionUser(@Valid @RequestBody ProvisionUserRequest request) {
        return userService.provisionUser(request.keycloakId(), provisionUserRequestMapper.toIdentityUser(request))
                .map(String::valueOf);
    }

    @GetMapping("resolve/{keycloakId}")
    public Mono<String> findBusinessIdByKeycloakId(
            @NotNull @PathVariable String keycloakId) {
        return userService.findBusinessIdByKeycloakId(keycloakId).map(String::valueOf);
    }

    @GetMapping
    public Mono<ResponseEntity<BusinessUser>> readProfile(
            @AuthenticationPrincipal Jwt jwt) {
        UUID userId = UUID.fromString(jwt.getClaimAsString("businessId"));

        return userService.readProfile(userId).map(ResponseEntity::ok);
    }

}
