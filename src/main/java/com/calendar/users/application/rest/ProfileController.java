package com.calendar.users.application.rest;

import com.calendar.users.domain.models.BusinessUser;
import com.calendar.users.domain.services.UserService;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("profile")
public class ProfileController {

    private final UserService userService;

    public ProfileController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("resolve")
    public Mono<ResponseEntity<Long>> resolveInternalUserId(@RequestHeader("X-Keycloak-Sub") String keycloakId) {
        return userService.resolveInternalUserId(keycloakId).map(ResponseEntity::ok);
    }

    @GetMapping
    public Mono<ResponseEntity<BusinessUser>> readProfile(
            @RequestHeader("X-Internal-User-Id") String userId) {
        return userService.readProfile(userId).map(ResponseEntity::ok);
    }

//    @PutMapping
//    public Mono<ResponseEntity> updateProfile(@RequestBody BusinessUser businessUser) {
//
//    }

    @PostMapping("profile/picture")
    public Mono<ResponseEntity<String>> updateProfilePicture(
            @RequestHeader("X-Keycloak-Sub") String keycloakId, @RequestPart("file") Mono<FilePart> filePartMono) {
        return userService.updateProfilePicture(keycloakId, filePartMono).map(ResponseEntity::ok);
    }
}
