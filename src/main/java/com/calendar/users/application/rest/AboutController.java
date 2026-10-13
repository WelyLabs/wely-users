package com.calendar.users.application.rest;

import com.calendar.users.application.dtos.AboutRequest;
import com.calendar.users.application.dtos.AboutResponse;
import com.calendar.users.application.mappers.AboutRestMapper;
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

/** The caller's own bio and interests: whose they are comes from the token. 400 USR-BUS-008 past the limits. */
@RestController
@RequestMapping("profile/about")
public class AboutController {

    private static final String BUSINESS_ID = "businessId";

    private final UserService userService;
    private final AboutRestMapper mapper;

    public AboutController(UserService userService, AboutRestMapper mapper) {
        this.userService = userService;
        this.mapper = mapper;
    }

    @GetMapping
    public Mono<AboutResponse> about(@AuthenticationPrincipal Jwt jwt) {
        return userService.about(UUID.fromString(jwt.getClaimAsString(BUSINESS_ID))).map(mapper::toResponse);
    }

    @PutMapping
    public Mono<AboutResponse> updateAbout(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody AboutRequest request) {
        return Mono.fromCallable(() -> mapper.toUserAbout(request))
                .flatMap(about -> userService.updateAbout(UUID.fromString(jwt.getClaimAsString(BUSINESS_ID)), about))
                .map(mapper::toResponse);
    }
}
