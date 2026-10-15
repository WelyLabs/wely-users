package com.calendar.users.domain.models;

import java.util.List;

/** One page of tombstones; next is null on the last page. */
public record DeletedUsersPage(List<DeletedUser> items, DeletedUser next) {

    public DeletedUsersPage {
        items = List.copyOf(items);
    }
}
