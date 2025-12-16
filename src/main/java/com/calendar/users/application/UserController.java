package com.calendar.users.application;

import com.calendar.users.domain.models.BusinessUser;
import com.calendar.users.domain.services.UserService;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("me")
    public Mono<ResponseEntity<BusinessUser>> readProfile(
            @RequestHeader("X-Internal-User-Id") String userId) {
        return userService.readProfile(userId).map(ResponseEntity::ok);
    }

    @PostMapping("signUp")
    public Mono<ResponseEntity<BusinessUser>> signUp(@RequestHeader("X-Keycloak-Sub") String keycloakId) {
        return userService.createUserOnSignUp(keycloakId).map(ResponseEntity::ok);
    }

    @PostMapping("me/profilePicture")
    public Mono<ResponseEntity<String>> updateProfilePicture(
            @RequestHeader("X-Keycloak-Sub") String keycloakId, @RequestPart("file") Mono<FilePart> filePartMono) {
        return userService.updateProfilePicture(keycloakId, filePartMono).map(ResponseEntity::ok);
    }

}
