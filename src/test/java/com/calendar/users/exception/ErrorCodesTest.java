package com.calendar.users.exception;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorCodesTest {

    @Test
    void of_shouldAnswerTheBusinessCode_whenTheFailureIsABusinessRefusal() {
        assertThat(ErrorCodes.of(new BusinessException(BusinessErrorCode.USER_NOT_FOUND))).isEqualTo("USR-BUS-001");
    }

    @Test
    void of_shouldAnswerTheTechnicalCode_whenADependencyFailed() {
        assertThat(ErrorCodes.of(new TechnicalException(TechnicalErrorCode.KEYCLOAK_ERROR))).isEqualTo("USR-TEC-001");
    }

    @Test
    void of_shouldAnswerTheClassName_neverTheMessage_otherwise() {
        assertThat(ErrorCodes.of(new IllegalStateException("http://user:s3cr3t@host"))).isEqualTo("IllegalStateException");
    }
}
