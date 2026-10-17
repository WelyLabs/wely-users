package com.calendar.users.domain.models;

import com.calendar.users.exception.BusinessErrorCode;
import com.calendar.users.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RegistrationTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 18);

    @Test
    void validOn_shouldAcceptTheRegistration_whenTheAgeIsWithinTheRange() {
        Registration registration = new Registration(LocalDate.of(1995, 3, 12));

        assertThat(registration.validOn(TODAY)).isSameAs(registration);
    }

    @Test
    void validOn_shouldAcceptTheBounds_whenTheUserTurns13TodayOrIs120() {
        assertThat(new Registration(TODAY.minusYears(13)).validOn(TODAY)).isNotNull();
        assertThat(new Registration(TODAY.minusYears(121).plusDays(1)).validOn(TODAY)).isNotNull();
    }

    @Test
    void validOn_shouldRefuse_whenTheUserIsYoungerThan13() {
        assertInvalid(TODAY.minusYears(13).plusDays(1));
    }

    @Test
    void validOn_shouldRefuse_whenTheUserIsOlderThan120() {
        assertInvalid(TODAY.minusYears(121));
    }

    @Test
    void validOn_shouldRefuse_whenTheDateIsTodayOrInTheFuture() {
        assertInvalid(TODAY);
        assertInvalid(TODAY.plusYears(1));
    }

    @Test
    void validOn_shouldRefuse_whenNoDateIsGiven() {
        assertInvalid(null);
    }

    private static void assertInvalid(LocalDate birthDate) {
        assertThatThrownBy(() -> new Registration(birthDate).validOn(TODAY))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(BusinessErrorCode.BIRTH_DATE_INVALID));
    }
}
