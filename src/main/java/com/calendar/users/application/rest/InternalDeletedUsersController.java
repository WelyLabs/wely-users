package com.calendar.users.application.rest;

import com.calendar.users.application.dtos.DeletedUsersPageResponse;
import com.calendar.users.application.mappers.DeletedUsersRestMapper;
import com.calendar.users.domain.services.AccountDeletionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Optional;

/** Internal listing of deleted accounts for the other services, behind the reader secret and the gateway block. */
@RestController
@RequestMapping("internal/deleted-users")
public class InternalDeletedUsersController {

    static final int MAX_LIMIT = 500;

    private final AccountDeletionService accountDeletionService;
    private final DeletedUsersRestMapper mapper;

    public InternalDeletedUsersController(AccountDeletionService accountDeletionService, DeletedUsersRestMapper mapper) {
        this.accountDeletionService = accountDeletionService;
        this.mapper = mapper;
    }

    /** The limit is clamped to [1, 500] rather than refused. */
    @GetMapping
    public Mono<DeletedUsersPageResponse> deletedUsers(@RequestParam(required = false) String after,
                                                       @RequestParam(defaultValue = "500") int limit) {
        int clamped = Math.clamp(limit, 1, MAX_LIMIT);
        return Mono.fromCallable(() -> Optional.ofNullable(mapper.decodeCursor(after)))
                .flatMap(cursor -> accountDeletionService.deletedUsers(cursor.orElse(null), clamped))
                .map(mapper::toResponse);
    }
}
