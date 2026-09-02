package com.razorrecall.service;

import com.razorrecall.domain.PaymentAttempt;
import com.razorrecall.domain.RecoveryCase;
import com.razorrecall.domain.RecoveryStatus;
import com.razorrecall.dto.RecoveryMetricsResponse;
import com.razorrecall.repository.RecoveryCaseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RecoveryReconciliationTest {

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
    void reconcilePayment_withValidWaitingCase_transitionsToRecovered() {
        String orderId = "order_rec_123";
        String paymentId = "pay_captured_123";
        BigDecimal amount = new BigDecimal("2500.00");

        PaymentAttempt attempt = new PaymentAttempt();
        attempt.setId(UUID.randomUUID());
        attempt.setOrderId(orderId);
        attempt.setStatus("FAILED");
        attempt.setAmount(amount);

        RecoveryCase rc = new RecoveryCase();
        rc.setId(UUID.randomUUID());
        rc.setPaymentAttempt(attempt);
        rc.setStatus(RecoveryStatus.WAITING_FOR_OUTCOME.name());
        rc.setEligible(true);
        rc.setCreatedAt(OffsetDateTime.now().minusMinutes(10));
        rc.setUpdatedAt(OffsetDateTime.now().minusMinutes(5));

        when(paymentAttemptService.findLatestByOrderId(orderId)).thenReturn(Optional.of(attempt));
        when(recoveryCaseRepository.findByPaymentAttemptId(attempt.getId())).thenReturn(Optional.of(rc));
        when(recoveryCaseRepository.save(any(RecoveryCase.class))).thenAnswer(inv -> inv.getArgument(0));

        RecoveryCaseService.ReconciliationResult result = recoveryCaseService.reconcileCapturedPayment(
                orderId, null, paymentId, amount
        );

        assertTrue(result.reconciled());
        assertNotNull(result.recoveryCase());
        assertEquals(RecoveryStatus.RECOVERED.name(), result.recoveryCase().getStatus());
        verify(paymentAttemptService).markPaymentCaptured(attempt, amount, paymentId);
        verify(recoveryCaseRepository).save(rc);
    }

    @Test
    void reconcilePayment_withActionPendingCase_transitionsToRecovered() {
        String orderId = "order_rec_456";
        String paymentId = "pay_captured_456";
        BigDecimal amount = new BigDecimal("1200.00");

        PaymentAttempt attempt = new PaymentAttempt();
        attempt.setId(UUID.randomUUID());
        attempt.setOrderId(orderId);
        attempt.setStatus("FAILED");
        attempt.setAmount(amount);

        RecoveryCase rc = new RecoveryCase();
        rc.setId(UUID.randomUUID());
        rc.setPaymentAttempt(attempt);
        rc.setStatus(RecoveryStatus.ACTION_PENDING.name());
        rc.setEligible(true);

        when(paymentAttemptService.findLatestByOrderId(orderId)).thenReturn(Optional.of(attempt));
        when(recoveryCaseRepository.findByPaymentAttemptId(attempt.getId())).thenReturn(Optional.of(rc));
        when(recoveryCaseRepository.save(any(RecoveryCase.class))).thenAnswer(inv -> inv.getArgument(0));

        RecoveryCaseService.ReconciliationResult result = recoveryCaseService.reconcileCapturedPayment(
                orderId, null, paymentId, amount
        );

        assertTrue(result.reconciled());
        assertEquals(RecoveryStatus.RECOVERED.name(), result.recoveryCase().getStatus());
        verify(paymentAttemptService).markPaymentCaptured(attempt, amount, paymentId);
    }

    @Test
    void reconcilePayment_withSecondaryPriority_referenceId_transitionsToRecovered() {
        UUID caseId = UUID.randomUUID();
        String paymentId = "pay_captured_789";
        BigDecimal amount = new BigDecimal("800.00");

        PaymentAttempt attempt = new PaymentAttempt();
        attempt.setId(UUID.randomUUID());
        attempt.setStatus("FAILED");
        attempt.setAmount(amount);

        RecoveryCase rc = new RecoveryCase();
        rc.setId(caseId);
        rc.setPaymentAttempt(attempt);
        rc.setStatus(RecoveryStatus.WAITING_FOR_OUTCOME.name());

        // order_id lookup returns empty
        when(recoveryCaseRepository.findById(caseId)).thenReturn(Optional.of(rc));
        when(recoveryCaseRepository.save(any(RecoveryCase.class))).thenAnswer(inv -> inv.getArgument(0));

        RecoveryCaseService.ReconciliationResult result = recoveryCaseService.reconcileCapturedPayment(
                null, caseId.toString(), paymentId, amount
        );

        assertTrue(result.reconciled());
        assertEquals(RecoveryStatus.RECOVERED.name(), result.recoveryCase().getStatus());
    }

    @Test
    void reconcilePayment_withNonWaitingCase_preservesStatus() {
        String orderId = "order_detected_1";
        PaymentAttempt attempt = new PaymentAttempt();
        attempt.setId(UUID.randomUUID());
        attempt.setOrderId(orderId);
        attempt.setStatus("FAILED");

        RecoveryCase rc = new RecoveryCase();
        rc.setId(UUID.randomUUID());
        rc.setPaymentAttempt(attempt);
        rc.setStatus(RecoveryStatus.DETECTED.name());

        when(paymentAttemptService.findLatestByOrderId(orderId)).thenReturn(Optional.of(attempt));
        when(recoveryCaseRepository.findByPaymentAttemptId(attempt.getId())).thenReturn(Optional.of(rc));

        RecoveryCaseService.ReconciliationResult result = recoveryCaseService.reconcileCapturedPayment(
                orderId, null, "pay_999", new BigDecimal("100.00")
        );

        assertFalse(result.reconciled());
        assertEquals(RecoveryStatus.DETECTED.name(), result.recoveryCase().getStatus());
        verify(recoveryCaseRepository, never()).save(any(RecoveryCase.class));
        verify(paymentAttemptService, never()).markPaymentCaptured(any(), any());
    }

    @Test
    void reconcilePayment_withAlreadyRecoveredCase_idempotent() {
        String orderId = "order_recovered_1";
        PaymentAttempt attempt = new PaymentAttempt();
        attempt.setId(UUID.randomUUID());
        attempt.setOrderId(orderId);
        attempt.setStatus("CAPTURED");

        RecoveryCase rc = new RecoveryCase();
        rc.setId(UUID.randomUUID());
        rc.setPaymentAttempt(attempt);
        rc.setStatus(RecoveryStatus.RECOVERED.name());

        when(paymentAttemptService.findLatestByOrderId(orderId)).thenReturn(Optional.of(attempt));
        when(recoveryCaseRepository.findByPaymentAttemptId(attempt.getId())).thenReturn(Optional.of(rc));

        RecoveryCaseService.ReconciliationResult result = recoveryCaseService.reconcileCapturedPayment(
                orderId, null, "pay_999", new BigDecimal("100.00")
        );

        assertTrue(result.reconciled());
        assertEquals(RecoveryStatus.RECOVERED.name(), result.recoveryCase().getStatus());
        verify(recoveryCaseRepository, never()).save(any(RecoveryCase.class));
    }

    @Test
    void reconcilePayment_withUnknownOrder_handlesGracefully() {
        when(paymentAttemptService.findLatestByOrderId("unknown_order")).thenReturn(Optional.empty());

        RecoveryCaseService.ReconciliationResult result = recoveryCaseService.reconcileCapturedPayment(
                "unknown_order", null, "pay_unmatched", new BigDecimal("500.00")
        );

        assertFalse(result.reconciled());
        assertNull(result.recoveryCase());
        assertTrue(result.message().contains("No matching recovery case"));
        verify(recoveryCaseRepository, never()).save(any(RecoveryCase.class));
    }

    @Test
    void calculateMetrics_returnsAccurateCountsAndAmounts() {
        PaymentAttempt att1 = createAttempt(new BigDecimal("1000.00"));
        PaymentAttempt att2 = createAttempt(new BigDecimal("2000.00"));
        PaymentAttempt att3 = createAttempt(new BigDecimal("3000.00"));
        PaymentAttempt att4 = createAttempt(new BigDecimal("4000.00"));
        PaymentAttempt att5 = createAttempt(new BigDecimal("5000.00"));

        RecoveryCase rc1 = createCase(RecoveryStatus.RECOVERED, att1, true);
        RecoveryCase rc2 = createCase(RecoveryStatus.ACTION_PENDING, att2, true);
        RecoveryCase rc3 = createCase(RecoveryStatus.WAITING_FOR_OUTCOME, att3, true);
        RecoveryCase rc4 = createCase(RecoveryStatus.ABSTAINED, att4, false);
        RecoveryCase rc5 = createCase(RecoveryStatus.ESCALATED, att5, false);

        when(recoveryCaseRepository.findAll()).thenReturn(List.of(rc1, rc2, rc3, rc4, rc5));

        RecoveryMetricsResponse metrics = recoveryCaseService.getMetrics();

        assertEquals(5, metrics.totalCases());
        assertEquals(1, metrics.recoveredCases());
        assertEquals(1, metrics.actionPendingCases());
        assertEquals(1, metrics.waitingForOutcomeCases());
        assertEquals(1, metrics.abstainedCases());
        assertEquals(1, metrics.escalatedCases());
        assertEquals(0, metrics.failedCases());

        assertEquals(new BigDecimal("15000.00"), metrics.totalAtRiskAmount());
        assertEquals(new BigDecimal("1000.00"), metrics.totalRecoveredAmount());

        // Actionable cases: rc1 (RECOVERED), rc2 (ACTION_PENDING), rc3 (WAITING_FOR_OUTCOME) = 3 actionable cases
        // Recovery rate = (1 / 3) * 100 = 33.33%
        assertEquals(new BigDecimal("33.33"), metrics.recoveryRatePercentage());
    }

    private PaymentAttempt createAttempt(BigDecimal amount) {
        PaymentAttempt attempt = new PaymentAttempt();
        attempt.setId(UUID.randomUUID());
        attempt.setAmount(amount);
        return attempt;
    }

    private RecoveryCase createCase(RecoveryStatus status, PaymentAttempt attempt, boolean eligible) {
        RecoveryCase rc = new RecoveryCase();
        rc.setId(UUID.randomUUID());
        rc.setStatus(status.name());
        rc.setPaymentAttempt(attempt);
        rc.setEligible(eligible);
        return rc;
    }
}
