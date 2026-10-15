package com.calendar.users.application.dtos;

import java.util.List;

/** One page of the internal listing; next is an opaque cursor, null on the last page. */
public record DeletedUsersPageResponse(List<DeletedUserItem> items, String next) {}
