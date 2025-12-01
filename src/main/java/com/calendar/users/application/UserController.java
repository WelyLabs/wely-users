package com.calendar.users.application;

import com.calendar.users.domain.models.BusinessUser;
import com.calendar.users.domain.services.UserService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    public String hello() {
        return "Hello World";
    }

    @GetMapping("me")
    public Mono<ResponseEntity<BusinessUser>> readProfile(@AuthenticationPrincipal Jwt jwt) {
        return userService.readProfile(jwt).map(ResponseEntity::ok);
    }
}
