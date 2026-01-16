package com.calendar.users.application.rest;

import com.calendar.users.domain.models.BusinessUser;
import com.calendar.users.domain.services.UserService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
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

//    @PutMapping
//    public Mono<ResponseEntity> updateProfile(@RequestBody BusinessUser businessUser) {
//
//    }

    // todo : faudra transférer ça aussi et changer le retour de l'url en vrai objet de réponse
    @PostMapping("picture")
    public Mono<ResponseEntity<String>> updateProfilePicture(
            @NotNull @RequestHeader("X-Internal-User-Id") Long userId, @RequestPart("file") Mono<FilePart> filePartMono) {
        return userService.updateProfilePicture(userId, filePartMono).map(ResponseEntity::ok);
    }
}
