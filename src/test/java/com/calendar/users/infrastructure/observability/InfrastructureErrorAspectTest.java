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

    // --- persistance -------------------------------------------------------

    @Test
    @DisplayName("une panne de base devient DATABASE_ERROR")
    void translatePersistenceFailures_shouldMapToDatabaseError() throws Throwable {
        when(joinPoint.proceed()).thenReturn(Mono.error(new RuntimeException("connection reset")));

        StepVerifier.create((Mono<?>) aspect.translatePersistenceFailures(joinPoint))
                .expectErrorMatches(error -> error instanceof TechnicalException
                        && codeOf(error) == TechnicalErrorCode.DATABASE_ERROR)
                .verify();
    }

    @Test
    @DisplayName("la règle métier de l'adaptateur traverse : contrainte unique ≠ incident")
    void translatePersistenceFailures_shouldLetBusinessFailuresThrough() throws Throwable {
        BusinessException alreadyExists = new BusinessException(BusinessErrorCode.USER_ALREADY_EXISTS);
        when(joinPoint.proceed()).thenReturn(Mono.error(alreadyExists));

        // L'adaptateur traduit DataIntegrityViolationException avant l'aspect. Si l'aspect
        // la réécrivait, un doublon de pseudo répondrait 500 au lieu de 409.
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

    // --- identité ----------------------------------------------------------

    @Test
    @DisplayName("un Keycloak injoignable devient KEYCLOAK_ERROR, pas DATABASE_ERROR")
    void translateIdentityFailures_shouldMapToKeycloakError() throws Throwable {
        when(joinPoint.proceed()).thenReturn(Mono.error(new RuntimeException("503 from admin API")));

        // Deux pointcuts distincts justement pour ça : confondre les deux incidents
        // rendrait le diagnostic en production impossible.
        StepVerifier.create((Mono<?>) aspect.translateIdentityFailures(joinPoint))
                .expectErrorMatches(error -> error instanceof TechnicalException
                        && codeOf(error) == TechnicalErrorCode.KEYCLOAK_ERROR)
                .verify();
    }

    // --- forme du retour ---------------------------------------------------

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
    @DisplayName("rien n'est souscrit à l'assemblage : l'advice ne rejoue pas la requête")
    void translatePersistenceFailures_shouldNotSubscribeEagerly() throws Throwable {
        Mono<String> lazy = Mono.fromSupplier(() -> {
            throw new AssertionError("le publisher ne doit pas être souscrit ici");
        });
        when(joinPoint.proceed()).thenReturn(lazy);

        assertThat(aspect.translatePersistenceFailures(joinPoint)).isInstanceOf(Mono.class);
    }
}
