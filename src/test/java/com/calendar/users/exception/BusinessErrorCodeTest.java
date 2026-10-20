package com.calendar.users.exception;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** The codes are the contract a client branches on: these guard the convention, not the wording. */
class BusinessErrorCodeTest {

    private static final Pattern CODE_FORMAT = Pattern.compile("^USR-(BUS|VAL|REQ)-\\d{3}$");

    private static List<String> codes() {
        return Arrays.stream(BusinessErrorCode.values()).map(BusinessErrorCode::getCode).toList();
    }

    @Test
    void values_shouldFollowTheCodeFormat_forEveryCode() {
        assertThat(codes()).allMatch(code -> CODE_FORMAT.matcher(code).matches());
    }

    @Test
    void values_shouldBeUnique_acrossTheEnum() {
        assertThat(codes()).doesNotHaveDuplicates();
    }

    @Test
    void values_shouldAnswerA4xxStatus_forEveryCode() {
        assertThat(BusinessErrorCode.values())
                .allMatch(error -> error.getHttpStatus().is4xxClientError());
    }

    @Test
    void values_shouldNotReuseAHandlerCode_whenTheHandlerWritesItAsALiteral() {
        assertThat(codes()).doesNotContain(
                GlobalErrorHandler.VALIDATION_ERROR_CODE,
                GlobalErrorHandler.REQUEST_ERROR_CODE,
                GlobalErrorHandler.UNEXPECTED_ERROR_CODE);
    }

    @Test
    void values_shouldNotOverlapTechnicalCodes() {
        List<String> technical = Arrays.stream(TechnicalErrorCode.values()).map(TechnicalErrorCode::getCode).toList();

        assertThat(codes()).doesNotContainAnyElementsOf(technical);
    }
}
