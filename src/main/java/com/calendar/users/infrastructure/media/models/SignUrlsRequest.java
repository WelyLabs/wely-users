package com.calendar.users.infrastructure.media.models;

import java.util.List;

/** The keys to sign, and for how long the URLs stay valid. */
public record SignUrlsRequest(List<String> keys, long ttlSeconds) {
}
