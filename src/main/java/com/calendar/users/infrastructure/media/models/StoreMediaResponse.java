package com.calendar.users.infrastructure.media.models;

/** wely-media's answer to an upload: the picture as stored after normalization. */
public record StoreMediaResponse(String key, int width, int height, String contentType) {
}
