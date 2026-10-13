package com.calendar.users.infrastructure.media.mappers;

import com.calendar.users.domain.models.StoredMedia;
import com.calendar.users.infrastructure.media.models.StoreMediaResponse;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MediaApiMapperTest {

    private final MediaApiMapper mapper = new MediaApiMapperImpl();

    @Test
    void toStoredMedia_shouldKeepTheKeyAndSize() {
        StoredMedia stored = mapper.toStoredMedia(new StoreMediaResponse("avatars/u1/a.jpg", 512, 480, "image/jpeg"));

        assertThat(stored).isEqualTo(new StoredMedia("avatars/u1/a.jpg", 512, 480));
    }

    @Test
    void toStoredMedia_shouldAnswerNull_forNoResponse() {
        assertThat(mapper.toStoredMedia(null)).isNull();
    }
}
