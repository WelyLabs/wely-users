package com.calendar.users.exception;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TechnicalErrorCounterTest {

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final TechnicalErrorCounter counter = new TechnicalErrorCounter(meterRegistry);

    private double counted(String code, String source) {
        Counter found = meterRegistry.find(GlobalErrorHandler.TECHNICAL_ERRORS_METRIC)
                .tags(GlobalErrorHandler.CODE_TAG, code, GlobalErrorHandler.SOURCE_TAG, source)
                .counter();
        return found == null ? 0 : found.count();
    }

    @Test
    void count_shouldUseTheCodeOfATechnicalFailure_andTagItsSource() {
        counter.count(new TechnicalException(TechnicalErrorCode.KAFKA_ERROR), GlobalErrorHandler.SOURCE_OUTBOX);
        counter.count(new TechnicalException(TechnicalErrorCode.KAFKA_ERROR), GlobalErrorHandler.SOURCE_OUTBOX);

        assertThat(counted("USR-TEC-003", GlobalErrorHandler.SOURCE_OUTBOX)).isEqualTo(2.0);
        assertThat(counted("USR-TEC-003", GlobalErrorHandler.SOURCE_HTTP)).isZero();
    }

    @Test
    void count_shouldIgnoreABusinessFailure_becauseARefusalIsNotAnIncident() {
        counter.count(new BusinessException(BusinessErrorCode.USER_NOT_FOUND), GlobalErrorHandler.SOURCE_JOB);

        assertThat(meterRegistry.find(GlobalErrorHandler.TECHNICAL_ERRORS_METRIC).counters()).isEmpty();
    }

    @Test
    void count_shouldCountAnythingElseAsUnexpected() {
        counter.count(new IllegalStateException("bug"), GlobalErrorHandler.SOURCE_HTTP);

        assertThat(counted(GlobalErrorHandler.UNEXPECTED_FAILURE_CODE, GlobalErrorHandler.SOURCE_HTTP)).isEqualTo(1.0);
    }

    @Test
    void count_shouldApplyTheSameRules_whenGivenACode() {
        counter.count("USR-TEC-001", GlobalErrorHandler.SOURCE_JOB);
        counter.count("USR-BUS-001", GlobalErrorHandler.SOURCE_JOB);
        counter.count("IllegalStateException", GlobalErrorHandler.SOURCE_JOB);

        assertThat(counted("USR-TEC-001", GlobalErrorHandler.SOURCE_JOB)).isEqualTo(1.0);
        assertThat(counted(GlobalErrorHandler.UNEXPECTED_FAILURE_CODE, GlobalErrorHandler.SOURCE_JOB)).isEqualTo(1.0);
        assertThat(meterRegistry.find(GlobalErrorHandler.TECHNICAL_ERRORS_METRIC).counters()).hasSize(2);
    }
}
