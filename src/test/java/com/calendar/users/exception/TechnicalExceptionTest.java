package com.calendar.users.exception;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TechnicalExceptionTest {

    @Test
    void constructor_shouldCarryTheCodeAndItsDetail_whenThereIsNoUnderlyingError() {
        TechnicalException exception = new TechnicalException(TechnicalErrorCode.KAFKA_ERROR);

        assertThat(exception.getErrorCode()).isEqualTo(TechnicalErrorCode.KAFKA_ERROR);
        assertThat(exception).hasMessage(TechnicalErrorCode.KAFKA_ERROR.getDetail());
        assertThat(exception.getCause()).isNull();
    }

    @Test
    void constructor_shouldKeepTheUnderlyingErrorAsCause_whenOneIsGiven() {
        RuntimeException underlying = new RuntimeException("connection refused");

        TechnicalException exception = new TechnicalException(TechnicalErrorCode.DATABASE_ERROR, underlying);

        assertThat(exception.getErrorCode()).isEqualTo(TechnicalErrorCode.DATABASE_ERROR);
        assertThat(exception).hasMessage(TechnicalErrorCode.DATABASE_ERROR.getDetail());
        assertThat(exception.getCause()).isSameAs(underlying);
    }
}
