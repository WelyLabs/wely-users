package com.calendar.users.domain.ports;

import com.calendar.users.domain.models.MediaProfile;
import com.calendar.users.domain.models.StoredMedia;
import reactor.core.publisher.Mono;

import java.util.Collection;
import java.util.Map;

/**
 * Where pictures are kept, privately: nothing stored is readable without a signed URL.
 *
 * <p>A key, not a URL, is what the rest of the system holds. The URL is computed when a picture is
 * shown, valid for a short while, so access can be granted and withdrawn per request.
 */
public interface MediaStore {

    /** Normalizes the upload for this profile and stores it; fails with PICTURE_INVALID or PICTURE_TOO_LARGE. */
    Mono<StoredMedia> store(String key, byte[] content, MediaProfile profile);

    /** Removing a missing key is not an error. */
    Mono<Void> delete(String key);

    /** Removes every picture stored under this prefix; a prefix with nothing under it is not an error. */
    Mono<Void> deletePrefix(String prefix);

    /** Key to signed URL, in one call; a key the store cannot sign is absent. */
    Mono<Map<String, String>> urls(Collection<String> keys);
}
