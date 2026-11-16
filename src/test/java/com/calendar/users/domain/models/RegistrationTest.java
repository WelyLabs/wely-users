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

    @Test
    void validOn_shouldAcceptTheRegistration_whenTheAgeIsWithinTheRange() {
        Registration registration = new Registration(BIRTH_DATE, "Léa", "Martin");

        assertThat(registration.validOn(TODAY, NAMELESS)).isEqualTo(registration);
    }

    @Test
    void validOn_shouldAcceptTheBounds_whenTheUserTurns13TodayOrIs120() {
        assertThat(new Registration(TODAY.minusYears(13), "Léa", "Martin").validOn(TODAY, NAMELESS)).isNotNull();
        assertThat(new Registration(TODAY.minusYears(121).plusDays(1), "Léa", "Martin").validOn(TODAY, NAMELESS)).isNotNull();
    }

    @Test
    void validOn_shouldRefuse_whenTheUserIsYoungerThan13() {
        assertInvalidDate(TODAY.minusYears(13).plusDays(1));
    }

    @Test
    void validOn_shouldRefuse_whenTheUserIsOlderThan120() {
        assertInvalidDate(TODAY.minusYears(121));
    }

    @Test
    void validOn_shouldRefuse_whenTheDateIsTodayOrInTheFuture() {
        assertInvalidDate(TODAY);
        assertInvalidDate(TODAY.plusYears(1));
    }

    @Test
    void validOn_shouldRefuse_whenNoDateIsGiven() {
        assertInvalidDate(null);
    }

    @Test
    void validOn_shouldTrimTheNames_whenTheyAreGiven() {
        Registration valid = new Registration(BIRTH_DATE, "  Léa ", "\tMartin\n").validOn(TODAY, NAMELESS);

        assertThat(valid).isEqualTo(new Registration(BIRTH_DATE, "Léa", "Martin"));
    }

    @Test
    void validOn_shouldAcceptNamesOfTheMaximumLength() {
        String longest = "a".repeat(Registration.MAX_NAME_LENGTH);

        assertThat(new Registration(BIRTH_DATE, longest, longest).validOn(TODAY, NAMELESS).firstName()).isEqualTo(longest);
    }

    @Test
    void validOn_shouldKeepTheStoredNames_whenNoneAreGivenAndTheUserHasBoth() {
        Registration birthDateOnly = new Registration(BIRTH_DATE, null, null);

        assertThat(birthDateOnly.validOn(TODAY, NAMED)).isEqualTo(birthDateOnly);
    }

    @Test
    void validOn_shouldRefuse_whenNoNamesAreGivenAndTheUserHasNone() {
        assertInvalidNames(new Registration(BIRTH_DATE, null, null), NAMELESS);
    }

    @Test
    void validOn_shouldRefuse_whenNoNamesAreGivenAndTheUserHasOnlyAFirstName() {
        BusinessUser firstNameOnly = new BusinessUser(null, "lea", 1, "Léa", " ", null, null);

        assertInvalidNames(new Registration(BIRTH_DATE, null, null), firstNameOnly);
    }

    @Test
    void validOn_shouldRefuse_whenOnlyOneNameIsGiven() {
        assertInvalidNames(new Registration(BIRTH_DATE, "Léa", null), NAMED);
        assertInvalidNames(new Registration(BIRTH_DATE, null, "Martin"), NAMED);
    }

    @Test
    void validOn_shouldRefuse_whenANameIsBlank() {
        assertInvalidNames(new Registration(BIRTH_DATE, "   ", "Martin"), NAMELESS);
        assertInvalidNames(new Registration(BIRTH_DATE, "Léa", ""), NAMELESS);
    }

    @Test
    void validOn_shouldRefuse_whenANameIsTooLong() {
        String tooLong = "a".repeat(Registration.MAX_NAME_LENGTH + 1);

        assertInvalidNames(new Registration(BIRTH_DATE, tooLong, "Martin"), NAMELESS);
    }

    @Test
    void validOn_shouldCheckTheBirthDateFirst_whenBothAreInvalid() {
        assertThatThrownBy(() -> new Registration(TODAY, null, null).validOn(TODAY, NAMELESS))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(BusinessErrorCode.BIRTH_DATE_INVALID));
    }

    private static void assertInvalidDate(LocalDate birthDate) {
        assertThatThrownBy(() -> new Registration(birthDate, "Léa", "Martin").validOn(TODAY, NAMELESS))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(BusinessErrorCode.BIRTH_DATE_INVALID));
    }

    private static void assertInvalidNames(Registration registration, BusinessUser current) {
        assertThatThrownBy(() -> registration.validOn(TODAY, current))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(BusinessErrorCode.NAMES_INVALID));
    }
}
