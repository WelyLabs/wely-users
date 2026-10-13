package com.calendar.users.domain.ports;

import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Where pictures are kept, privately: nothing stored is readable without a signed URL.
 *
 * <p>A key, not a URL, is what the rest of the system holds. The URL is computed when a picture is
 * shown, valid for a short while, so access can be granted and withdrawn per request.
 */
public interface PictureStorage {

    Mono<Void> put(String key, byte[] content, String contentType);

    /** Removing a missing key is not an error. */
    Mono<Void> delete(String key);

    /** A URL that reads this key until {@code ttl} has passed, computed locally without a network call. */
    String signedUrl(String key, Duration ttl);
}
