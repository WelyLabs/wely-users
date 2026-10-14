package com.calendar.users.application.rest;

import com.calendar.users.application.dtos.PreferencesRequest;
import com.calendar.users.application.dtos.PreferencesResponse;
import com.calendar.users.application.mappers.PreferencesRestMapper;
import com.calendar.users.domain.services.UserService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** The caller's own preferences, today the app's theme: whose they are comes from the token. */
@RestController
@RequestMapping("profile/preferences")
public class PreferencesController {

    private static final String BUSINESS_ID = "businessId";

    private final UserService userService;
    private final PreferencesRestMapper mapper;

    public PreferencesController(UserService userService, PreferencesRestMapper mapper) {
        this.userService = userService;
        this.mapper = mapper;
    }

    @GetMapping
    public Mono<PreferencesResponse> preferences(@AuthenticationPrincipal Jwt jwt) {
        return userService.preferences(UUID.fromString(jwt.getClaimAsString(BUSINESS_ID))).map(mapper::toResponse);
    }

    @PutMapping
    public Mono<PreferencesResponse> updatePreferences(@AuthenticationPrincipal Jwt jwt,
                                                       @Valid @RequestBody PreferencesRequest request) {
        return userService.updatePreferences(UUID.fromString(jwt.getClaimAsString(BUSINESS_ID)),
                        mapper.toUserPreferences(request))
                .map(mapper::toResponse);
    }
}
