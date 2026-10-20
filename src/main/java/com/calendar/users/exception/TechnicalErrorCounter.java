package com.calendar.users.exception;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The one place that increments {@value GlobalErrorHandler#TECHNICAL_ERRORS_METRIC}, for HTTP
 * answers and background loops alike, so the {@code code} and {@code source} tags are built once.
 *
 * <p>A {@link TechnicalException} counts under its code, a {@link BusinessException} not at all —
 * a refused request or record is an outcome, not an incident — and anything else under
 * {@link GlobalErrorHandler#UNEXPECTED_FAILURE_CODE}.
 */
@Component
public class TechnicalErrorCounter {

    private static final Set<String> TECHNICAL_CODES = Arrays.stream(TechnicalErrorCode.values())
            .map(TechnicalErrorCode::getCode)
            .collect(Collectors.toUnmodifiableSet());
    private static final Set<String> BUSINESS_CODES = Arrays.stream(BusinessErrorCode.values())
            .map(BusinessErrorCode::getCode)
            .collect(Collectors.toUnmodifiableSet());

    private final MeterRegistry meterRegistry;

    public TechnicalErrorCounter(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void count(Throwable error, String source) {
        if (error instanceof BusinessException) {
            return;
        }
        increment(error instanceof TechnicalException technical
                ? technical.getErrorCode().getCode()
                : GlobalErrorHandler.UNEXPECTED_FAILURE_CODE, source);
    }

    /**
     * For a failure a domain service already reduced to a code, as {@link ErrorCodes#of} does:
     * same rules, read from the code — technical counted as is, business skipped, anything else
     * (a class name) unexpected.
     */
    public void count(String errorCode, String source) {
        if (BUSINESS_CODES.contains(errorCode)) {
            return;
        }
        increment(TECHNICAL_CODES.contains(errorCode) ? errorCode : GlobalErrorHandler.UNEXPECTED_FAILURE_CODE, source);
    }

    private void increment(String code, String source) {
        meterRegistry.counter(GlobalErrorHandler.TECHNICAL_ERRORS_METRIC,
                GlobalErrorHandler.CODE_TAG, code,
                GlobalErrorHandler.SOURCE_TAG, source).increment();
    }
}
