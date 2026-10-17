package com.calendar.users.domain.models;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.MonthDay;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FriendProfileTest {

    private static final UUID ID = UUID.randomUUID();
    private static final LocalDateTime JOINED = LocalDateTime.of(2026, 1, 15, 10, 30);

    @Test
    void of_shouldCombineTheProfileAndAbout_withoutThePicture() {
        BusinessUser user = new BusinessUser(ID, "lea", 4242, "Léa", "Martin", "avatars/x.jpg", JOINED);

        assertThat(FriendProfile.of(user, new UserAbout("Partante", List.of("Rando"))))
                .isEqualTo(new FriendProfile(ID, "lea", 4242, "Léa", "Martin", JOINED, "Partante", List.of("Rando"), null));
    }

    @Test
    void of_shouldKeepTheBirthdayWithoutItsYear_whenTheUserHasABirthDate() {
        BusinessUser user = new BusinessUser(ID, "lea", 4242, "Léa", "Martin", null, JOINED,
                LocalDate.of(1995, 3, 12), true);

        assertThat(FriendProfile.of(user, UserAbout.EMPTY).birthday()).isEqualTo(MonthDay.of(3, 12));
    }

    @Test
    void constructor_shouldTurnNoInterestsIntoAnEmptyList() {
        assertThat(new FriendProfile(ID, "lea", 1, null, null, JOINED, null, null, null).interests()).isEmpty();
    }

    @Test
    void constructor_shouldCopyTheInterests_soTheyCannotChangeAfterwards() {
        List<String> interests = new ArrayList<>(List.of("Rando"));
        FriendProfile profile = new FriendProfile(ID, "lea", 1, null, null, JOINED, null, interests, null);
        interests.add("Ciné");

        assertThat(profile.interests()).containsExactly("Rando");
        assertThatThrownBy(() -> profile.interests().add("x")).isInstanceOf(UnsupportedOperationException.class);
    }
}
