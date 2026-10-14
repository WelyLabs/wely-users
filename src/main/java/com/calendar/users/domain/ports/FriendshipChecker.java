package com.calendar.users.domain.ports;

import reactor.core.publisher.Mono;

/** Whether two users are friends, as the social graph knows it. */
public interface FriendshipChecker {

    Mono<Boolean> areFriends(String viewerId, String userId);
}
