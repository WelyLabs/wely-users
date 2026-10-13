package com.calendar.users.domain.models;

import com.calendar.users.exception.BusinessErrorCode;
import com.calendar.users.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserAboutTest {

    private static void assertInvalid(String bio, List<String> interests) {
        assertThatThrownBy(() -> new UserAbout(bio, interests))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(BusinessErrorCode.ABOUT_INVALID));
    }

    @Test
    void constructor_shouldTidyTheBio() {
        assertThat(new UserAbout("  Partante   pour tout  ", List.of()).bio()).isEqualTo("Partante pour tout");
        assertThat(new UserAbout("   ", List.of()).bio()).isNull();
        assertThat(new UserAbout(null, null).bio()).isNull();
    }

    @Test
    void constructor_shouldKeepEachInterestOnce_whateverItsCase_andDropBlanks() {
        UserAbout about = new UserAbout(null, Arrays.asList(" Rando ", "rando", "Jeux  de société", "", null, "Ciné"));

        assertThat(about.interests()).containsExactly("Rando", "Jeux de société", "Ciné");
    }

    @Test
    void constructor_shouldRefuseALongBio_ratherThanCutIt() {
        assertInvalid("x".repeat(UserAbout.MAX_BIO_LENGTH + 1), List.of());
        assertThat(new UserAbout("x".repeat(UserAbout.MAX_BIO_LENGTH), List.of()).bio()).hasSize(UserAbout.MAX_BIO_LENGTH);
    }

    @Test
    void constructor_shouldRefuseTooManyOrTooLongInterests() {
        assertInvalid(null, List.of("a", "b", "c", "d", "e", "f"));
        assertInvalid(null, List.of("x".repeat(UserAbout.MAX_INTEREST_LENGTH + 1)));
    }

    @Test
    void constructor_shouldCountInterestsAfterRemovingDuplicates() {
        // Six typed, five distinct: accepted.
        assertThat(new UserAbout(null, List.of("a", "b", "c", "d", "e", "E")).interests()).hasSize(5);
    }

    @Test
    void empty_shouldHoldNothing() {
        assertThat(UserAbout.EMPTY.bio()).isNull();
        assertThat(UserAbout.EMPTY.interests()).isEmpty();
    }
}
