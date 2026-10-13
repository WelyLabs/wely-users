package com.calendar.users.domain.models;

import java.util.UUID;

/**
 * A user's picture as stored: a storage key for an uploaded picture, or a path the web app serves
 * itself, like the demo accounts' avatars.
 */
public record UserPicture(UUID userId, String reference) {

    /** Uploaded pictures live under this prefix; anything else is served as it is. */
    public static final String STORED_PREFIX = "avatars/";

    public boolean isStored() {
        return reference != null && reference.startsWith(STORED_PREFIX);
    }
}
