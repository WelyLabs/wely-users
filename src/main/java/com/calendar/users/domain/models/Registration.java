package com.calendar.users.domain.models;

import com.calendar.users.exception.BusinessErrorCode;
import com.calendar.users.exception.BusinessException;

import java.time.LocalDate;
import java.time.Period;

/** What completing the signup asks for. Today the birth date only; later steps add their fields here. */
public record Registration(LocalDate birthDate) {

    public static final int MIN_AGE = 13;
    public static final int MAX_AGE = 120;

    /** This registration if the birth date is in the past and gives an age of 13 to 120 today; BIRTH_DATE_INVALID otherwise. */
    public Registration validOn(LocalDate today) {
        if (birthDate == null || !birthDate.isBefore(today)) {
            throw new BusinessException(BusinessErrorCode.BIRTH_DATE_INVALID);
        }
        int age = Period.between(birthDate, today).getYears();
        if (age < MIN_AGE || age > MAX_AGE) {
            throw new BusinessException(BusinessErrorCode.BIRTH_DATE_INVALID);
        }
        return this;
    }
}
