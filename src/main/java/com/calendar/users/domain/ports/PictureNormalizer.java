package com.calendar.users.domain.ports;

import reactor.core.publisher.Mono;

/**
 * Turns an uploaded file into the picture actually stored: decoded, so a file that is not an image
 * is refused, then squared, resized and re-encoded, which also drops its metadata — a phone photo's
 * EXIF block can carry where it was taken.
 */
public interface PictureNormalizer {

    /** Fails with PICTURE_INVALID when the upload is not an image this service accepts. */
    Mono<byte[]> normalize(byte[] upload);

    /** The content type of what {@link #normalize} produces. */
    String contentType();
}
