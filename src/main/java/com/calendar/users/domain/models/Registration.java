package com.calendar.users.domain.models;

import com.calendar.users.exception.BusinessErrorCode;
import com.calendar.users.exception.BusinessException;

import java.time.LocalDate;
import java.time.Period;

/**
 * One pass of the progressive signup completion: any of the fields the profile still misses, and
 * the identity provider's signup form does not ask. A null field is left as stored.
 */
public record Registration(LocalDate birthDate, String firstName, String lastName) {

    public static final int MIN_AGE = 13;
    public static final int MAX_AGE = 120;
    /** The profile form's limit; the columns are TEXT. */
    public static final int MAX_NAME_LENGTH = 255;

    /**
     * This registration with trimmed names. REGISTRATION_EMPTY when it gives no field;
     * BIRTH_DATE_INVALID for a birth date not in the past or an age outside 13 to 120 today;
     * NAMES_INVALID for a blank or too long name.
     */
    public Registration validOn(LocalDate today) {
        if (birthDate == null && firstName == null && lastName == null) {
            throw new BusinessException(BusinessErrorCode.REGISTRATION_EMPTY);
        }
        if (birthDate != null) {
            int age = Period.between(birthDate, today).getYears();
            if (!birthDate.isBefore(today) || age < MIN_AGE || age > MAX_AGE) {
                throw new BusinessException(BusinessErrorCode.BIRTH_DATE_INVALID);
            }
        }
        return new Registration(birthDate, validName(firstName), validName(lastName));
    }

    /** Whether {@code current}, once given this registration, misses nothing the signup asks. */
    public boolean completes(BusinessUser current) {
        return (birthDate != null || current.birthDate() != null)
                && isGiven(firstName != null ? firstName : current.firstName())
                && isGiven(lastName != null ? lastName : current.lastName());
    }

    private static boolean isGiven(String name) {
        return name != null && !name.isBlank();
    }

    /** Null stays null: the name is not part of this pass. */
    private static String validName(String name) {
        if (name == null) {
            return null;
        }
        String trimmed = name.strip();
        if (trimmed.isEmpty() || trimmed.length() > MAX_NAME_LENGTH) {
            throw new BusinessException(BusinessErrorCode.NAMES_INVALID);
        }
        return trimmed;
    }
}
