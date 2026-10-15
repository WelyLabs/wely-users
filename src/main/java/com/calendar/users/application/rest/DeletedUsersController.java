package com.calendar.users.application.rest;

import com.calendar.users.application.dtos.DeletedUsersRequest;
import com.calendar.users.application.dtos.DeletedUsersResponse;
import com.calendar.users.domain.services.AccountDeletionService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;

/** Which of these users deleted their account, for any signed-in user; a POST keeps the ids out of access logs. */
@RestController
@RequestMapping("users/deleted")
public class DeletedUsersController {

    private final AccountDeletionService accountDeletionService;

    public DeletedUsersController(AccountDeletionService accountDeletionService) {
        this.accountDeletionService = accountDeletionService;
    }

    @PostMapping
    public Mono<DeletedUsersResponse> deletedUsers(@Valid @RequestBody DeletedUsersRequest request) {
        return accountDeletionService.deletedAmong(request.userIds())
                .map(ids -> new DeletedUsersResponse(List.copyOf(ids)));
    }
}
