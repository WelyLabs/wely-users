package com.calendar.users.domain.models;

import com.calendar.users.exception.BusinessErrorCode;
import com.calendar.users.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RegistrationTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 18);
    private static final LocalDate BIRTH_DATE = LocalDate.of(1995, 3, 12);
    /** Provisioned from an Auth0 signup, which asks no names. */
    private static final BusinessUser NAMELESS = new BusinessUser(null, "lea", 1, null, null, null, null);
    private static final BusinessUser NAMED = new BusinessUser(null, "lea", 1, "Léa", "Martin", null, null);
    private static final BusinessUser NAMED_WITH_BIRTH_DATE =
            new BusinessUser(null, "lea", 1, "Léa", "Martin", null, null, BIRTH_DATE, true);

    // --- validOn ---------------------------------------------------------------

    @Test
    void validOn_shouldAcceptTheRegistration_whenEveryFieldIsValid() {
        Registration registration = new Registration(BIRTH_DATE, "Léa", "Martin");

        assertThat(registration.validOn(TODAY)).isEqualTo(registration);
    }

    @Test
    void validOn_shouldAcceptAPartialRegistration_whenOnlyOneFieldIsSent() {
        assertThat(new Registration(BIRTH_DATE, null, null).validOn(TODAY)).isEqualTo(new Registration(BIRTH_DATE, null, null));
        assertThat(new Registration(null, "Léa", null).validOn(TODAY)).isEqualTo(new Registration(null, "Léa", null));
        assertThat(new Registration(null, null, "Martin").validOn(TODAY)).isEqualTo(new Registration(null, null, "Martin"));
    }

    @Test
    void validOn_shouldRefuseWithRegistrationEmpty_whenNoFieldIsSent() {
        assertRefused(new Registration(null, null, null), BusinessErrorCode.REGISTRATION_EMPTY);
    }

    @Test
    void validOn_shouldAcceptTheBounds_whenTheUserTurns13TodayOrIs120() {
        assertThat(new Registration(TODAY.minusYears(13), null, null).validOn(TODAY)).isNotNull();
        assertThat(new Registration(TODAY.minusYears(121).plusDays(1), null, null).validOn(TODAY)).isNotNull();
    }

    @Test
    void validOn_shouldRefuse_whenTheUserIsYoungerThan13() {
        assertRefused(new Registration(TODAY.minusYears(13).plusDays(1), null, null), BusinessErrorCode.BIRTH_DATE_INVALID);
    }

    @Test
    void validOn_shouldRefuse_whenTheUserIsOlderThan120() {
        assertRefused(new Registration(TODAY.minusYears(121), null, null), BusinessErrorCode.BIRTH_DATE_INVALID);
    }

    @Test
    void validOn_shouldRefuse_whenTheDateIsTodayOrInTheFuture() {
        assertRefused(new Registration(TODAY, "Léa", "Martin"), BusinessErrorCode.BIRTH_DATE_INVALID);
        assertRefused(new Registration(TODAY.plusYears(1), null, null), BusinessErrorCode.BIRTH_DATE_INVALID);
    }

    @Test
    void validOn_shouldTrimTheNames_whenTheyAreSent() {
        Registration valid = new Registration(null, "  Léa ", "\tMartin\n").validOn(TODAY);

        assertThat(valid).isEqualTo(new Registration(null, "Léa", "Martin"));
    }

    @Test
    void validOn_shouldAcceptNamesOfTheMaximumLength() {
        String longest = "a".repeat(Registration.MAX_NAME_LENGTH);

        assertThat(new Registration(null, longest, longest).validOn(TODAY).firstName()).isEqualTo(longest);
    }

    @Test
    void validOn_shouldRefuse_whenASentNameIsBlank() {
        assertRefused(new Registration(null, "   ", "Martin"), BusinessErrorCode.NAMES_INVALID);
        assertRefused(new Registration(BIRTH_DATE, null, ""), BusinessErrorCode.NAMES_INVALID);
    }

    @Test
    void validOn_shouldRefuse_whenANameIsTooLong() {
        assertRefused(new Registration(null, "a".repeat(Registration.MAX_NAME_LENGTH + 1), "Martin"),
                BusinessErrorCode.NAMES_INVALID);
    }

    // --- completes -------------------------------------------------------------

    @Test
    void completes_shouldBeTrue_whenTheRegistrationFillsEveryMissingField() {
        assertThat(new Registration(BIRTH_DATE, "Léa", "Martin").completes(NAMELESS)).isTrue();
        assertThat(new Registration(BIRTH_DATE, null, null).completes(NAMED)).isTrue();
    }

    @Test
    void completes_shouldBeTrue_whenTheProfileAlreadyHadEverything() {
        assertThat(new Registration(LocalDate.of(1996, 4, 2), null, null).completes(NAMED_WITH_BIRTH_DATE)).isTrue();
    }

    @Test
    void completes_shouldBeFalse_whenAFieldStaysMissing() {
        assertThat(new Registration(BIRTH_DATE, null, null).completes(NAMELESS)).isFalse();
        assertThat(new Registration(null, "Léa", "Martin").completes(NAMELESS)).isFalse();
        assertThat(new Registration(BIRTH_DATE, "Léa", null).completes(NAMELESS)).isFalse();
    }

    @Test
    void completes_shouldTreatABlankStoredNameAsMissing() {
        BusinessUser blankLastName = new BusinessUser(null, "lea", 1, "Léa", " ", null, null, BIRTH_DATE, false);

        assertThat(new Registration(LocalDate.of(1996, 4, 2), null, null).completes(blankLastName)).isFalse();
        assertThat(new Registration(null, null, "Martin").completes(blankLastName)).isTrue();
    }

    private static void assertRefused(Registration registration, BusinessErrorCode code) {
        assertThatThrownBy(() -> registration.validOn(TODAY))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(code));
    }
}
