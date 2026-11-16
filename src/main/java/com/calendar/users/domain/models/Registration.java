package com.calendar.users.domain.models;

import com.calendar.users.exception.BusinessErrorCode;
import com.calendar.users.exception.BusinessException;

import java.time.LocalDate;
import java.time.Period;

/**
 * What completing the signup asks for: the birth date, and the names the identity provider's
 * signup form does not collect. Names may be left out only by a user who already has both, so the
 * same call still changes the birth date alone later.
 */
public record Registration(LocalDate birthDate, String firstName, String lastName) {

    public static final int MIN_AGE = 13;
    public static final int MAX_AGE = 120;
    /** The profile form's limit; the columns are TEXT. */
    public static final int MAX_NAME_LENGTH = 255;

    /**
     * This registration with trimmed names, or with none when it gives none and {@code current} has
     * both. BIRTH_DATE_INVALID unless the birth date is in the past and gives an age of 13 to 120
     * today; NAMES_INVALID for a blank or too long name, or for names missing from both.
     */
    public Registration validOn(LocalDate today, BusinessUser current) {
        if (birthDate == null || !birthDate.isBefore(today)) {
            throw new BusinessException(BusinessErrorCode.BIRTH_DATE_INVALID);
        }
        int age = Period.between(birthDate, today).getYears();
        if (age < MIN_AGE || age > MAX_AGE) {
            throw new BusinessException(BusinessErrorCode.BIRTH_DATE_INVALID);
        }
        if (firstName == null && lastName == null && isGiven(current.firstName()) && isGiven(current.lastName())) {
            return this;
        }
        return new Registration(birthDate, validName(firstName), validName(lastName));
    }

    private static boolean isGiven(String name) {
        return name != null && !name.isBlank();
    }

    private static String validName(String name) {
        String trimmed = name == null ? "" : name.strip();
        if (trimmed.isEmpty() || trimmed.length() > MAX_NAME_LENGTH) {
            throw new BusinessException(BusinessErrorCode.NAMES_INVALID);
        }
        return trimmed;
    }
}
