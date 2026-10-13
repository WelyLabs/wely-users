package com.calendar.users.application.rest;

import com.calendar.users.application.dtos.AvatarsRequest;
import com.calendar.users.domain.services.UserService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;

/**
 * Picture URLs, for any signed-in user. A POST rather than a GET with ids in the query string: a
 * screen can show a hundred people, and the ids stay out of access logs.
 */
@RestController
@RequestMapping("avatars")
public class AvatarController {

    private final UserService userService;

    public AvatarController(UserService userService) {
        this.userService = userService;
    }

    /** User id to URL; users without a picture are absent. */
    @PostMapping
    public Mono<Map<UUID, String>> pictureUrls(@Valid @RequestBody AvatarsRequest request) {
        return userService.pictureUrls(request.userIds());
    }
}
