package com.calendar.users.infrastructure.media.models;

import java.util.Map;

/** Key to signed URL. */
public record SignUrlsResponse(Map<String, String> urls) {
}
