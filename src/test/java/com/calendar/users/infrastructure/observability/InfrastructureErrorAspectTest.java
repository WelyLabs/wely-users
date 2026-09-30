package com.calendar.users.infrastructure.observability;

import com.calendar.users.exception.BusinessErrorCode;
import com.calendar.users.exception.BusinessException;
import com.calendar.users.exception.TechnicalErrorCode;
import com.calendar.users.exception.TechnicalException;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InfrastructureErrorAspectTest {

    @Mock private ProceedingJoinPoint joinPoint;
    @Mock private Signature signature;

    private InfrastructureErrorAspect aspect;

    @BeforeEach
    void setUp() {
        aspect = new InfrastructureErrorAspect();
        when(joinPoint.getSignature()).thenReturn(signature);
        when(signature.getName()).thenReturn("findIdByKeycloakId");
    }

    private static TechnicalErrorCode codeOf(Throwable error) {
        return ((TechnicalException) error).getErrorCode();
    }

    // --- persistence -------------------------------------------------------

    @Test
    @DisplayName("a database failure becomes DATABASE_ERROR")
    void translatePersistenceFailures_shouldMapToDatabaseError() throws Throwable {
        when(joinPoint.proceed()).thenReturn(Mono.error(new RuntimeException("connection reset")));

        StepVerifier.create((Mono<?>) aspect.translatePersistenceFailures(joinPoint))
                .expectErrorMatches(error -> error instanceof TechnicalException
                        && codeOf(error) == TechnicalErrorCode.DATABASE_ERROR)
                .verify();
    }

    @Test
    @DisplayName("the adapter's business rule passes through: a unique constraint is not an incident")
    void translatePersistenceFailures_shouldLetBusinessFailuresThrough() throws Throwable {
        BusinessException alreadyExists = new BusinessException(BusinessErrorCode.USER_ALREADY_EXISTS);
        when(joinPoint.proceed()).thenReturn(Mono.error(alreadyExists));

        // The adapter translates DataIntegrityViolationException before the aspect. Were
        // the aspect to rewrite it, a duplicate handle would answer 500 instead of 409.
        StepVerifier.create((Mono<?>) aspect.translatePersistenceFailures(joinPoint))
                .expectErrorMatches(error -> error == alreadyExists)
                .verify();
    }

    @Test
    void translatePersistenceFailures_shouldNotRewrapTechnicalFailures() throws Throwable {
        TechnicalException already = new TechnicalException(TechnicalErrorCode.DATABASE_ERROR);
        when(joinPoint.proceed()).thenReturn(Mono.error(already));

        StepVerifier.create((Mono<?>) aspect.translatePersistenceFailures(joinPoint))
                .expectErrorMatches(error -> error == already)
                .verify();
    }

    // --- identity ----------------------------------------------------------

    @Test
    @DisplayName("an unreachable Keycloak becomes KEYCLOAK_ERROR, not DATABASE_ERROR")
    void translateIdentityFailures_shouldMapToKeycloakError() throws Throwable {
        when(joinPoint.proceed()).thenReturn(Mono.error(new RuntimeException("503 from admin API")));

        // Two separate pointcuts exist for this: conflating the two incidents would make
        // a production diagnosis impossible.
        StepVerifier.create((Mono<?>) aspect.translateIdentityFailures(joinPoint))
                .expectErrorMatches(error -> error instanceof TechnicalException
                        && codeOf(error) == TechnicalErrorCode.KEYCLOAK_ERROR)
                .verify();
    }

    // --- shape of the return value -----------------------------------------

    @Test
    void translatePersistenceFailures_shouldHandleFluxAsWell() throws Throwable {
        when(joinPoint.proceed()).thenReturn(Flux.error(new IllegalStateException("pool exhausted")));

        StepVerifier.create((Flux<?>) aspect.translatePersistenceFailures(joinPoint))
                .expectError(TechnicalException.class)
                .verify();
    }

    @Test
    void translatePersistenceFailures_shouldLeaveSuccessfulResultsAlone() throws Throwable {
        when(joinPoint.proceed()).thenReturn(Mono.just("value"));

        @SuppressWarnings("unchecked")
        Mono<String> wrapped = (Mono<String>) aspect.translatePersistenceFailures(joinPoint);

        StepVerifier.create(wrapped)
                .expectNext("value")
                .verifyComplete();
    }

    @Test
    void translatePersistenceFailures_shouldPassThroughNonReactiveReturns() throws Throwable {
        when(joinPoint.proceed()).thenReturn(42);

        assertThat(aspect.translatePersistenceFailures(joinPoint)).isEqualTo(42);
    }

    @Test
    @DisplayName("nothing is subscribed at assembly: the advice does not replay the query")
    void translatePersistenceFailures_shouldNotSubscribeEagerly() throws Throwable {
        Mono<String> lazy = Mono.fromSupplier(() -> {
            throw new AssertionError("the publisher must not be subscribed here");
        });
        when(joinPoint.proceed()).thenReturn(lazy);

        assertThat(aspect.translatePersistenceFailures(joinPoint)).isInstanceOf(Mono.class);
    }

    @Test
    @DisplayName("the pointcut declarations are what bind the advice to each adapter package")
    void pointcutDeclarations_shouldExistForBothAdapterPackages() {
        // Empty by construction: a @Pointcut method is a named selector, and Spring reads the
        // annotation, never the body. Called here because the convention is that every public
        // method has a test, and because deleting one silently unbinds the advice — which
        // InfrastructureErrorAspectWiringTest is what actually catches.
        InfrastructureErrorAspect aspect = new InfrastructureErrorAspect();

        assertThatCode(aspect::persistenceAdapterMethod).doesNotThrowAnyException();
        assertThatCode(aspect::identityAdapterMethod).doesNotThrowAnyException();
    }
}
