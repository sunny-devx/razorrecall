package com.razorrecall.service;

import com.razorrecall.domain.PaymentAttempt;
import com.razorrecall.domain.RecoveryCase;
import com.razorrecall.domain.RecoveryStatus;
import com.razorrecall.domain.RecoveryStrategy;
import com.razorrecall.dto.ActionExecutionResult;
import com.razorrecall.repository.RecoveryCaseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RecoverySchedulerServiceTest {

    @Nested
    @DisplayName("Spring Context Conditional Loading Tests")
    class ConditionalLoadingTests {

        @Nested
        @SpringBootTest(properties = {
                "razorrecall.webhook.secret=test_webhook_secret_key_12345"
        })
        class DefaultDisabledTest {

            @Autowired
            private ApplicationContext applicationContext;

            @Test
            @DisplayName("Scheduler bean must NOT be loaded when razorrecall.scheduler.enabled is absent or false")
            void testSchedulerDisabledByDefault() {
                assertFalse(
                        applicationContext.containsBean("recoverySchedulerService"),
                        "RecoverySchedulerService bean must NOT be loaded into Spring context by default"
                );
            }
        }

        @Nested
        @SpringBootTest(properties = {
                "razorrecall.scheduler.enabled=true",
                "razorrecall.webhook.secret=test_webhook_secret_key_12345"
        })
        class ExplicitlyEnabledTest {

            @Autowired
            private ApplicationContext applicationContext;

            @Autowired(required = false)
            private RecoverySchedulerService schedulerService;

            @Test
            @DisplayName("Scheduler bean must be loaded when razorrecall.scheduler.enabled=true")
            void testSchedulerEnabledExplicitly() {
                assertTrue(
                        applicationContext.containsBean("recoverySchedulerService"),
                        "RecoverySchedulerService bean must be registered when enabled=true"
                );
                assertNotNull(schedulerService, "RecoverySchedulerService bean must be injected");
            }
        }
    }

    @Nested
    @DisplayName("Scheduler Execution & Isolation Unit Tests")
    class SchedulerExecutionTests {

        @Mock
        private RecoveryCaseService recoveryCaseService;

        private Clock fixedClock;
        private RecoverySchedulerService schedulerService;

        @BeforeEach
        void setUp() {
            Instant fixedInstant = Instant.parse("2026-09-04T12:00:00Z");
            fixedClock = Clock.fixed(fixedInstant, ZoneOffset.UTC);
            schedulerService = new RecoverySchedulerService(recoveryCaseService, 24, fixedClock);
        }

        @Test
        @DisplayName("scheduleEvaluation invokes evaluateDetectedCases and logs count")
        void testScheduleEvaluationSuccess() {
            RecoveryCase rc = new RecoveryCase();
            rc.setId(UUID.randomUUID());
            RecoveryCaseService.EvaluationResult evalResult = new RecoveryCaseService.EvaluationResult(
                    rc,
                    RecoveryStrategy.PAYMENT_LINK,
                    RecoveryStatus.ACTION_PENDING,
                    OffsetDateTime.now(),
                    "Soft failure; payment link recommended"
            );

            when(recoveryCaseService.evaluateDetectedCases()).thenReturn(List.of(evalResult));

            int evaluatedCount = schedulerService.scheduleEvaluation();

            assertEquals(1, evaluatedCount);
            verify(recoveryCaseService, times(1)).evaluateDetectedCases();
        }

        @Test
        @DisplayName("scheduleDispatch invokes dispatchDueCases and logs count")
        void testScheduleDispatchSuccess() {
            UUID caseId = UUID.randomUUID();
            ActionExecutionResult dispatchResult = new ActionExecutionResult(
                    caseId,
                    RecoveryStrategy.PAYMENT_LINK,
                    RecoveryStatus.WAITING_FOR_OUTCOME,
                    "plink_123",
                    "https://rzp.io/i/123",
                    OffsetDateTime.now(),
                    "Payment link generated"
            );

            when(recoveryCaseService.dispatchDueCases()).thenReturn(List.of(dispatchResult));

            int dispatchedCount = schedulerService.scheduleDispatch();

            assertEquals(1, dispatchedCount);
            verify(recoveryCaseService, times(1)).dispatchDueCases();
        }

        @Test
        @DisplayName("scheduleExpiration calculates cutoff based on expiryWindowHours and invokes expireStaleCases")
        void testScheduleExpirationSuccess() {
            OffsetDateTime expectedCutoff = OffsetDateTime.now(fixedClock).minusHours(24);

            RecoveryCase expiredCase = new RecoveryCase();
            expiredCase.setId(UUID.randomUUID());
            expiredCase.setStatus(RecoveryStatus.EXPIRED.name());

            when(recoveryCaseService.expireStaleCases(eq(expectedCutoff))).thenReturn(List.of(expiredCase));

            int expiredCount = schedulerService.scheduleExpiration();

            assertEquals(1, expiredCount);
            verify(recoveryCaseService, times(1)).expireStaleCases(eq(expectedCutoff));
        }

        @Test
        @DisplayName("runAutonomousCycle runs evaluation, dispatch, and expiration in sequence and returns summary")
        void testRunAutonomousCycle() {
            OffsetDateTime expectedCutoff = OffsetDateTime.now(fixedClock).minusHours(24);

            RecoveryCase rc = new RecoveryCase();
            rc.setId(UUID.randomUUID());
            RecoveryCaseService.EvaluationResult evalResult = new RecoveryCaseService.EvaluationResult(
                    rc,
                    RecoveryStrategy.SMART_RETRY,
                    RecoveryStatus.ACTION_PENDING,
                    OffsetDateTime.now(),
                    "Smart retry scheduled"
            );

            ActionExecutionResult dispatchResult = new ActionExecutionResult(
                    rc.getId(),
                    RecoveryStrategy.SMART_RETRY,
                    RecoveryStatus.WAITING_FOR_OUTCOME,
                    "retry_123",
                    null,
                    OffsetDateTime.now(),
                    "Smart retry dispatched"
            );

            RecoveryCase expCase = new RecoveryCase();
            expCase.setId(UUID.randomUUID());
            expCase.setStatus(RecoveryStatus.EXPIRED.name());

            when(recoveryCaseService.evaluateDetectedCases()).thenReturn(List.of(evalResult));
            when(recoveryCaseService.dispatchDueCases()).thenReturn(List.of(dispatchResult));
            when(recoveryCaseService.expireStaleCases(eq(expectedCutoff))).thenReturn(List.of(expCase));

            RecoverySchedulerService.AutonomousCycleSummary summary = schedulerService.runAutonomousCycle();

            assertNotNull(summary);
            assertEquals(1, summary.evaluated());
            assertEquals(1, summary.dispatched());
            assertEquals(1, summary.expired());

            verify(recoveryCaseService, times(1)).evaluateDetectedCases();
            verify(recoveryCaseService, times(1)).dispatchDueCases();
            verify(recoveryCaseService, times(1)).expireStaleCases(eq(expectedCutoff));
        }

        @Test
        @DisplayName("Error isolation: scheduleEvaluation catches Exception and returns 0 without propagating")
        void testScheduleEvaluationErrorIsolation() {
            when(recoveryCaseService.evaluateDetectedCases()).thenThrow(new RuntimeException("Database timeout"));

            int count = schedulerService.scheduleEvaluation();

            assertEquals(0, count);
        }

        @Test
        @DisplayName("Error isolation: scheduleDispatch catches Exception and returns 0 without propagating")
        void testScheduleDispatchErrorIsolation() {
            when(recoveryCaseService.dispatchDueCases()).thenThrow(new RuntimeException("Gateway network error"));

            int count = schedulerService.scheduleDispatch();

            assertEquals(0, count);
        }

        @Test
        @DisplayName("Error isolation: scheduleExpiration catches Exception and returns 0 without propagating")
        void testScheduleExpirationErrorIsolation() {
            when(recoveryCaseService.expireStaleCases(any())).thenThrow(new RuntimeException("Lock contention error"));

            int count = schedulerService.scheduleExpiration();

            assertEquals(0, count);
        }

        @Test
        @DisplayName("Error isolation in cycle: Exception in evaluation does not prevent dispatch or expiration")
        void testCycleErrorIsolation() {
            OffsetDateTime expectedCutoff = OffsetDateTime.now(fixedClock).minusHours(24);

            when(recoveryCaseService.evaluateDetectedCases()).thenThrow(new RuntimeException("Evaluation failure"));
            when(recoveryCaseService.dispatchDueCases()).thenReturn(List.of());
            when(recoveryCaseService.expireStaleCases(eq(expectedCutoff))).thenReturn(List.of());

            RecoverySchedulerService.AutonomousCycleSummary summary = schedulerService.runAutonomousCycle();

            assertNotNull(summary);
            assertEquals(0, summary.evaluated());
            assertEquals(0, summary.dispatched());
            assertEquals(0, summary.expired());

            verify(recoveryCaseService, times(1)).dispatchDueCases();
            verify(recoveryCaseService, times(1)).expireStaleCases(eq(expectedCutoff));
        }
    }

    @Nested
    @DisplayName("Domain Expiration Logic Tests in RecoveryCaseService")
    class DomainExpirationTests {

        @Mock
        private RecoveryCaseRepository recoveryCaseRepository;

        @Mock
        private FailureClassifier failureClassifier;

        @Mock
        private RecoveryDecisionEngine recoveryDecisionEngine;

        @Mock
        private RecoveryGuardrailService recoveryGuardrailService;

        @Mock
        private RecoveryActionDispatcher recoveryActionDispatcher;

        @Mock
        private PaymentAttemptService paymentAttemptService;

        private RecoveryCaseService recoveryCaseService;

        @BeforeEach
        void setUp() {
            recoveryCaseService = new RecoveryCaseService(
                    recoveryCaseRepository,
                    failureClassifier,
                    recoveryDecisionEngine,
                    recoveryGuardrailService,
                    recoveryActionDispatcher,
                    paymentAttemptService
            );
        }

        @Test
        @DisplayName("expireStaleCases transitions stale WAITING_FOR_OUTCOME cases to EXPIRED and clears nextActionAt")
        void testExpireStaleCasesTransitionsToExpired() {
            OffsetDateTime now = OffsetDateTime.now();
            OffsetDateTime cutoff = now.minusHours(24);

            RecoveryCase staleCase = new RecoveryCase();
            staleCase.setId(UUID.randomUUID());
            staleCase.setStatus(RecoveryStatus.WAITING_FOR_OUTCOME.name());
            staleCase.setCreatedAt(cutoff.minusHours(2)); // created 26 hours ago
            staleCase.setNextActionAt(now.plusHours(1));

            when(recoveryCaseRepository.findByStatusAndCreatedAtBefore(RecoveryStatus.WAITING_FOR_OUTCOME.name(), cutoff))
                    .thenReturn(List.of(staleCase));
            when(recoveryCaseRepository.save(any(RecoveryCase.class))).thenAnswer(invocation -> invocation.getArgument(0));

            List<RecoveryCase> expired = recoveryCaseService.expireStaleCases(cutoff);

            assertEquals(1, expired.size());
            RecoveryCase result = expired.getFirst();
            assertEquals(RecoveryStatus.EXPIRED.name(), result.getStatus());
            assertNull(result.getNextActionAt(), "nextActionAt must be cleared for expired cases");
            assertNotNull(result.getUpdatedAt());

            ArgumentCaptor<RecoveryCase> captor = ArgumentCaptor.forClass(RecoveryCase.class);
            verify(recoveryCaseRepository, times(1)).save(captor.capture());
            assertEquals(RecoveryStatus.EXPIRED.name(), captor.getValue().getStatus());
        }

        @Test
        @DisplayName("expireStaleCases rejects null cutoff with IllegalArgumentException")
        void testExpireStaleCasesRequiresNonNullCutoff() {
            assertThrows(IllegalArgumentException.class, () -> recoveryCaseService.expireStaleCases(null));
        }

        @Test
        @DisplayName("expireStaleCases ignores empty candidate list without error")
        void testExpireStaleCasesEmptyList() {
            OffsetDateTime cutoff = OffsetDateTime.now().minusHours(24);
            when(recoveryCaseRepository.findByStatusAndCreatedAtBefore(RecoveryStatus.WAITING_FOR_OUTCOME.name(), cutoff))
                    .thenReturn(List.of());

            List<RecoveryCase> expired = recoveryCaseService.expireStaleCases(cutoff);

            assertTrue(expired.isEmpty());
            verify(recoveryCaseRepository, never()).save(any());
        }

        @Test
        @DisplayName("Repeated expiration calls are idempotent and process no already-expired cases")
        void testRepeatedExpirationIsIdempotent() {
            OffsetDateTime cutoff = OffsetDateTime.now().minusHours(24);

            RecoveryCase staleCase = new RecoveryCase();
            staleCase.setId(UUID.randomUUID());
            staleCase.setStatus(RecoveryStatus.WAITING_FOR_OUTCOME.name());
            staleCase.setCreatedAt(cutoff.minusHours(5));

            when(recoveryCaseRepository.findByStatusAndCreatedAtBefore(RecoveryStatus.WAITING_FOR_OUTCOME.name(), cutoff))
                    .thenReturn(List.of(staleCase))
                    .thenReturn(List.of()); // Second call finds zero because status was updated to EXPIRED

            when(recoveryCaseRepository.save(any(RecoveryCase.class))).thenAnswer(invocation -> invocation.getArgument(0));

            List<RecoveryCase> firstRun = recoveryCaseService.expireStaleCases(cutoff);
            assertEquals(1, firstRun.size());
            assertEquals(RecoveryStatus.EXPIRED.name(), firstRun.getFirst().getStatus());

            List<RecoveryCase> secondRun = recoveryCaseService.expireStaleCases(cutoff);
            assertEquals(0, secondRun.size());
        }

        @Test
        @DisplayName("Reconciliation strictly rejects payments for EXPIRED cases")
        void testReconciliationRejectsExpiredCases() {
            PaymentAttempt attempt = new PaymentAttempt();
            UUID attemptId = UUID.randomUUID();
            attempt.setId(attemptId);
            attempt.setOrderId("order_stale_123");
            attempt.setAmount(new BigDecimal("2500.00"));

            RecoveryCase expiredCase = new RecoveryCase();
            expiredCase.setId(UUID.randomUUID());
            expiredCase.setStatus(RecoveryStatus.EXPIRED.name());
            expiredCase.setPaymentAttempt(attempt);

            when(paymentAttemptService.findLatestByOrderId("order_stale_123")).thenReturn(Optional.of(attempt));
            when(recoveryCaseRepository.findByPaymentAttemptId(attemptId)).thenReturn(Optional.of(expiredCase));

            RecoveryCaseService.ReconciliationResult result = recoveryCaseService.reconcileCapturedPayment(
                    "order_stale_123",
                    null,
                    "pay_exp_999",
                    new BigDecimal("2500.00")
            );

            assertFalse(result.reconciled(), "Expired case must not be reconciled to RECOVERED");
            assertTrue(result.message().contains("EXPIRED"));
            verify(paymentAttemptService, never()).markPaymentCaptured(any(), any(), any());
        }

        @Test
        @DisplayName("Cases in RECOVERED status are never targeted for expiration")
        void testRecoveredCasesProtectedFromExpiration() {
            OffsetDateTime cutoff = OffsetDateTime.now().minusHours(24);

            // Repository query only seeks WAITING_FOR_OUTCOME cases
            when(recoveryCaseRepository.findByStatusAndCreatedAtBefore(RecoveryStatus.WAITING_FOR_OUTCOME.name(), cutoff))
                    .thenReturn(List.of());

            List<RecoveryCase> expired = recoveryCaseService.expireStaleCases(cutoff);

            assertTrue(expired.isEmpty());
            verify(recoveryCaseRepository, times(1))
                    .findByStatusAndCreatedAtBefore(RecoveryStatus.WAITING_FOR_OUTCOME.name(), cutoff);
        }
    }
}
