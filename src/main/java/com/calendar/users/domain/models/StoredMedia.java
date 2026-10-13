package com.calendar.users.domain.models;

/** A picture as stored: its key and its size after normalization. */
public record StoredMedia(String key, int width, int height) {
}
