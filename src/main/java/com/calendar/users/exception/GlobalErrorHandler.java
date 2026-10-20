package com.calendar.users.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns every failure into an RFC 7807 {@code application/problem+json} response.
 *
 * <p>The three services that had error handling each answered in a different shape — a
 * custom record here, a bare {@code Map} in events, nothing at all in chat — so a client
 * had to know which service it was talking to before it could read an error. RFC 7807 is
 * the standard for this and {@link ProblemDetail} ships with Spring, so there is no reason
 * to invent a fourth shape.
 *
 * <p>Three properties are added beyond the standard fields: {@code code}, the stable
 * identifier a client branches on, {@code timestamp}, and {@code requestId} — the id the
 * gateway generated for the request (see {@link RequestIds}), which ends every log line here, so
 * a response a user reports leads straight to its logs, in every service it went through.
 *
 * <p>Every technical failure answered here also increments {@value #TECHNICAL_ERRORS_METRIC}
 * through {@link TechnicalErrorCounter}, tagged with its code and source {@value #SOURCE_HTTP},
 * so an outage shows on a dashboard before anyone reads a log.
 *
 * <h2>Why first</h2>
 *
 * <p>Ordered ahead of every other advice: Boot ships its own problem-details advice at order 0,
 * and whichever advice is consulted first answers. Ours declares a handler for
 * {@link Exception}, so placed first it answers every controller failure and nothing reaches a
 * shape without {@code code} and {@code requestId}. Failures outside controllers — security,
 * web filters — go through {@link #toProblem} instead, via {@link ProblemResponseWriter}.
 */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class GlobalErrorHandler {

    /** Namespace for the {@code type} URI. Not dereferenced; it identifies, it does not serve. */
    private static final String PROBLEM_TYPE_BASE = "https://welylabs.app/problems/";

    /** Bean Validation rejected the body. Not an enum value: the framework, not a rule, raises it. */
    public static final String VALIDATION_FAILED_CODE = "USR-VAL-001";

    /** The framework refused the request itself: unknown path, wrong method, unreadable body. */
    public static final String REQUEST_REFUSED_CODE = "USR-REQ-000";

    /** A failure nothing translated: a bug, not a known dependency outage. */
    public static final String UNEXPECTED_FAILURE_CODE = "USR-TEC-000";

    /** The codes this handler writes itself, as opposed to those carried by an enum. */
    public static final List<String> HANDLER_CODES =
            List.of(VALIDATION_FAILED_CODE, REQUEST_REFUSED_CODE, UNEXPECTED_FAILURE_CODE);

    /** Exported to Prometheus as {@code wely_errors_technical_total{code="..."}}. */
    public static final String TECHNICAL_ERRORS_METRIC = "wely.errors.technical";

    /** Tag of {@link #TECHNICAL_ERRORS_METRIC} carrying the error code. */
    public static final String CODE_TAG = "code";

    /** Tag of {@link #TECHNICAL_ERRORS_METRIC} saying where the failure surfaced; one of the SOURCE_ values. */
    public static final String SOURCE_TAG = "source";

    /** Answered to an HTTP caller: the advice, the web exception handler, the security writers. */
    public static final String SOURCE_HTTP = "http";

    /** A Kafka consumer. */
    public static final String SOURCE_KAFKA = "kafka";

    /** An outbox relay or dispatcher. */
    public static final String SOURCE_OUTBOX = "outbox";

    /** A deleted-users reconciliation loop. */
    public static final String SOURCE_RECONCILIATION = "reconciliation";

    /** A scheduled or periodic background job. */
    public static final String SOURCE_JOB = "job";

    /** Problem property holding the request id, see {@link RequestIds}. */
    public static final String REQUEST_ID_PROPERTY = "requestId";

    private final TechnicalErrorCounter technicalErrors;

    public GlobalErrorHandler(TechnicalErrorCounter technicalErrors) {
        this.technicalErrors = technicalErrors;
    }

    /**
     * The same mapping as the handlers below, for failures no controller raised: what the
     * security chain refuses and what a web filter throws.
     */
    public ProblemDetail toProblem(Throwable error, ServerWebExchange exchange) {
        return switch (error) {
            case BusinessException business -> handleBusinessException(business, exchange);
            case TechnicalException technical -> handleTechnicalException(technical, exchange);
            case WebExchangeBindException invalid -> handleValidationException(invalid, exchange);
            case ResponseStatusException refused -> handleResponseStatusException(refused, exchange);
            default -> unexpected(error, exchange);
        };
    }

    @ExceptionHandler(BusinessException.class)
    public ProblemDetail handleBusinessException(BusinessException ex, ServerWebExchange exchange) {
        BusinessErrorCode error = ex.getErrorCode();

        // Expected outcome, not an incident: logged at WARN, and without a stack trace.
        log.warn("Business failure {} on {} (request {})", error.getCode(), exchange.getRequest().getPath(),
                RequestIds.of(exchange));

        return problem(error.getHttpStatus(), error.getCode(), error.getTitle(), error.getDetail(),
                exchange);
    }

    @ExceptionHandler(TechnicalException.class)
    public ProblemDetail handleTechnicalException(TechnicalException ex, ServerWebExchange exchange) {
        TechnicalErrorCode error = ex.getErrorCode();

        // A dependency is down: ERROR with the cause, because someone has to act on it.
        // The previous version logged these at WARN, which hid them.
        log.error("Technical failure {} on {} (request {})", error.getCode(), exchange.getRequest().getPath(),
                RequestIds.of(exchange), ex);
        technicalErrors.count(ex, SOURCE_HTTP);

        return problem(error.getHttpStatus(), error.getCode(), error.getTitle(), error.getDetail(),
                exchange);
    }

    /** Bean Validation rejections, with the offending fields listed. */
    @ExceptionHandler(WebExchangeBindException.class)
    public ProblemDetail handleValidationException(WebExchangeBindException ex,
                                                  ServerWebExchange exchange) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(error -> fieldErrors.put(error.getField(), error.getDefaultMessage()));

        log.debug("Validation failure {} on {} (request {})", VALIDATION_FAILED_CODE,
                exchange.getRequest().getPath(), RequestIds.of(exchange));

        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, VALIDATION_FAILED_CODE,
                "Invalid request", "One or more fields are invalid.", exchange);
        problem.setProperty("errors", fieldErrors);

        return problem;
    }

    /**
     * Statuses the framework itself decided: an unknown path, a method that does not apply, a
     * body it cannot read.
     *
     * <p>Without this they fall through to the catch-all below and every one of them answers 500.
     * A missing page reported as a server fault is wrong twice over — it misleads the caller, and
     * it fills the logs with "unhandled failure" for requests that were handled exactly as they
     * should have been.
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ProblemDetail handleResponseStatusException(ResponseStatusException ex,
                                                       ServerWebExchange exchange) {
        HttpStatus status = HttpStatus.valueOf(ex.getStatusCode().value());
        log.debug("{} on {} (request {})", status, exchange.getRequest().getPath(), RequestIds.of(exchange));

        return problem(status, REQUEST_REFUSED_CODE, status.getReasonPhrase(),
                "The request could not be served as sent.", exchange);
    }

    /**
     * Last resort. The detail is deliberately generic: an exception message can carry a
     * query, a constraint name or a host, and none of that belongs in a response. The real
     * message goes to the logs, where the request id ties the two together.
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpectedException(Exception ex, ServerWebExchange exchange) {
        return unexpected(ex, exchange);
    }

    private ProblemDetail unexpected(Throwable ex, ServerWebExchange exchange) {
        log.error("Unhandled failure {} on {} (request {})", UNEXPECTED_FAILURE_CODE, exchange.getRequest().getPath(),
                RequestIds.of(exchange), ex);
        technicalErrors.count(ex, SOURCE_HTTP);

        return problem(HttpStatus.INTERNAL_SERVER_ERROR, UNEXPECTED_FAILURE_CODE,
                "Unexpected error", "The request could not be completed.", exchange);
    }

    private ProblemDetail problem(HttpStatus status, String code, String title, String detail,
                                  ServerWebExchange exchange) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(PROBLEM_TYPE_BASE + code.toLowerCase()));
        problem.setTitle(title);
        problem.setInstance(URI.create(exchange.getRequest().getPath().value()));
        problem.setProperty("code", code);
        problem.setProperty("timestamp", Instant.now().toString());
        problem.setProperty(REQUEST_ID_PROPERTY, RequestIds.of(exchange));

        return problem;
    }
}
