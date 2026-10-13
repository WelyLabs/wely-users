package com.calendar.users.domain.models;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class UserPictureTest {

    private static final UUID ID = UUID.randomUUID();

    @Test
    void isStored_shouldBeTrueForAnUpload() {
        assertThat(new UserPicture(ID, "avatars/" + ID + "/a.jpg").isStored()).isTrue();
    }

    @Test
    void isStored_shouldBeFalseForAPathTheWebAppServesOrNothing() {
        assertThat(new UserPicture(ID, "/demo/avatars/camille.svg").isStored()).isFalse();
        assertThat(new UserPicture(ID, null).isStored()).isFalse();
    }
}
