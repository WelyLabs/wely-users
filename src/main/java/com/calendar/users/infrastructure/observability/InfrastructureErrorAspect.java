package com.calendar.users.infrastructure.observability;

import com.calendar.users.exception.BusinessException;
import com.calendar.users.exception.TechnicalErrorCode;
import com.calendar.users.exception.TechnicalException;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Logs and translates infrastructure failures for every adapter, once.
 *
 * <p>Six adapter methods repeated the same shape: log the failure, map it to a
 * {@code TechnicalException}. The code differs by dependency, so there are two pointcuts
 * rather than one — a PostgreSQL timeout and an unreachable Keycloak are not the same
 * incident and should not surface under the same error code.
 *
 * <h2>The reactive catch</h2>
 *
 * <p>A plain {@code @AfterThrowing} advice would catch almost nothing. These methods do not
 * throw — they return a {@link Mono} or {@link Flux} immediately, and the failure arrives
 * later, on another thread, as an error signal inside that publisher. The advice therefore
 * wraps the returned publisher rather than guarding the call, and must not subscribe while
 * doing so, which would run the query twice.
 *
 * <h2>Order with the adapters' own mapping</h2>
 *
 * <p>An adapter may still translate a specific failure itself — {@code save} turns a
 * {@code DataIntegrityViolationException} into {@code USER_ALREADY_EXISTS}, which is a
 * business rule, not a database incident. That mapping is applied inside the method, so it
 * runs first; this advice wraps the result and lets any {@link BusinessException} through
 * untouched.
 *
 * <h2>What is deliberately not logged</h2>
 *
 * <p>Method arguments. {@code save} receives a whole user and {@code resolve} a Keycloak
 * subject id; an aspect that logs {@code args} puts them in the cluster logs. The method
 * name and the exception message locate a failure well enough.
 */
@Slf4j
@Aspect
@Component
public class InfrastructureErrorAspect {

    @Pointcut("execution(public * com.calendar.users.infrastructure.persistence.adapters.*.*(..))")
    public void persistenceAdapterMethod() {
        // Pointcut declaration only.
    }

    @Pointcut("execution(public * com.calendar.users.infrastructure.identity.adapters.*.*(..))")
    public void identityAdapterMethod() {
        // Pointcut declaration only.
    }

    @Around("persistenceAdapterMethod()")
    public Object translatePersistenceFailures(ProceedingJoinPoint joinPoint) throws Throwable {
        return wrap(joinPoint, "PostgreSQL", TechnicalErrorCode.DATABASE_ERROR);
    }

    @Around("identityAdapterMethod()")
    public Object translateIdentityFailures(ProceedingJoinPoint joinPoint) throws Throwable {
        return wrap(joinPoint, "Keycloak", TechnicalErrorCode.KEYCLOAK_ERROR);
    }

    private Object wrap(ProceedingJoinPoint joinPoint, String dependency, TechnicalErrorCode code)
            throws Throwable {
        String operation = joinPoint.getSignature().getName();
        Object result = joinPoint.proceed();

        if (result instanceof Mono<?> mono) {
            return mono.onErrorMap(error -> translate(dependency, code, operation, error));
        }
        if (result instanceof Flux<?> flux) {
            return flux.onErrorMap(error -> translate(dependency, code, operation, error));
        }

        return result;
    }

    /**
     * Domain failures pass through: a {@link BusinessException} is the adapter stating a
     * business outcome, and an already-translated {@link TechnicalException} must not be
     * wrapped twice.
     */
    private Throwable translate(String dependency, TechnicalErrorCode code, String operation,
                                Throwable error) {
        if (error instanceof BusinessException || error instanceof TechnicalException) {
            return error;
        }

        log.error("{} failure in {}: {}", dependency, operation, error.getMessage(), error);
        return new TechnicalException(code);
    }
}
