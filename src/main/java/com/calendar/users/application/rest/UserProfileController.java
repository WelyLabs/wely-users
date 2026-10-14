package com.calendar.users.application.rest;

import com.calendar.users.application.dtos.FriendProfileResponse;
import com.calendar.users.application.mappers.FriendProfileRestMapper;
import com.calendar.users.domain.services.UserService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** Another user's profile, for their friends: 403 USR-BUS-009 otherwise, 502 USR-TEC-005 when wely-social is down. */
@RestController
@RequestMapping("users")
public class UserProfileController {

    private static final String BUSINESS_ID = "businessId";

    private final UserService userService;
    private final FriendProfileRestMapper mapper;

    public UserProfileController(UserService userService, FriendProfileRestMapper mapper) {
        this.userService = userService;
        this.mapper = mapper;
    }

    @GetMapping("{userId}/profile")
    public Mono<FriendProfileResponse> friendProfile(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID userId) {
        UUID viewerId = UUID.fromString(jwt.getClaimAsString(BUSINESS_ID));

        return userService.friendProfile(viewerId, userId).map(mapper::toResponse);
    }
}
