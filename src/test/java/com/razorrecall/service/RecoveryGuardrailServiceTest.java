package com.razorrecall.service;

import com.razorrecall.domain.PaymentAttempt;
import com.razorrecall.domain.RecoveryCase;
import com.razorrecall.domain.RecoveryStatus;
import com.razorrecall.domain.RecoveryStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RecoveryGuardrailServiceTest {

    private RecoveryGuardrailService guardrailService;
    private Clock fixedClock;

    @BeforeEach
    void setUp() {
        fixedClock = Clock.fixed(Instant.parse("2026-09-02T12:00:00Z"), ZoneOffset.UTC);
        guardrailService = new RecoveryGuardrailService(new BigDecimal("500000.00"), fixedClock);
    }

    private RecoveryCase createDetectedCase(String failureClass, boolean eligible, BigDecimal amount, String currency) {
        PaymentAttempt attempt = new PaymentAttempt();
        attempt.setId(UUID.randomUUID());
        attempt.setAmount(amount);
        attempt.setCurrency(currency);
        attempt.setStatus("FAILED");

        RecoveryCase rc = new RecoveryCase();
        rc.setId(UUID.randomUUID());
        rc.setPaymentAttempt(attempt);
        rc.setFailureClass(failureClass);
        rc.setEligible(eligible);
        rc.setStatus(RecoveryStatus.DETECTED.name());
        return rc;
    }

    @Test
    void testValidDetectedEligibleCase_ActionPending() {
        RecoveryCase rc = createDetectedCase("SOFT", true, new BigDecimal("2500.00"), "INR");
        RecoveryDecisionEngine.RecoveryDecision decision = new RecoveryDecisionEngine.RecoveryDecision(
                RecoveryStrategy.SMART_RETRY,
                300,
                "Gateway timeout"
        );

        RecoveryGuardrailService.GuardrailResult result = guardrailService.validate(rc, decision);

        assertTrue(result.passed());
        assertEquals(RecoveryStatus.ACTION_PENDING, result.targetStatus());
        assertNotNull(result.nextActionAt());
        assertEquals(Instant.parse("2026-09-02T12:05:00Z"), result.nextActionAt().toInstant());
    }

    @Test
    void testIneligibleCase_Abstained() {
        RecoveryCase rc = createDetectedCase("SOFT", false, new BigDecimal("1000.00"), "INR");
        RecoveryDecisionEngine.RecoveryDecision decision = new RecoveryDecisionEngine.RecoveryDecision(
                RecoveryStrategy.ABSTAIN,
                0,
                "Case is not eligible"
        );

        RecoveryGuardrailService.GuardrailResult result = guardrailService.validate(rc, decision);

        assertTrue(result.passed());
        assertEquals(RecoveryStatus.ABSTAINED, result.targetStatus());
        assertNull(result.nextActionAt());
    }

    @Test
    void testHardFailureCase_Abstained() {
        RecoveryCase rc = createDetectedCase("HARD", true, new BigDecimal("1000.00"), "INR");
        RecoveryDecisionEngine.RecoveryDecision decision = new RecoveryDecisionEngine.RecoveryDecision(
                RecoveryStrategy.ABSTAIN,
                0,
                "Hard card failure"
        );

        RecoveryGuardrailService.GuardrailResult result = guardrailService.validate(rc, decision);

        assertTrue(result.passed());
        assertEquals(RecoveryStatus.ABSTAINED, result.targetStatus());
        assertNull(result.nextActionAt());
    }

    @Test
    void testTerminalRecoveredCase_Rejected() {
        RecoveryCase rc = createDetectedCase("SOFT", true, new BigDecimal("500.00"), "INR");
        rc.setStatus(RecoveryStatus.RECOVERED.name());

        RecoveryDecisionEngine.RecoveryDecision decision = new RecoveryDecisionEngine.RecoveryDecision(
                RecoveryStrategy.SMART_RETRY,
                300,
                "Retry"
        );

        RecoveryGuardrailService.GuardrailResult result = guardrailService.validate(rc, decision);

        assertFalse(result.passed());
        assertEquals(RecoveryStatus.RECOVERED, result.targetStatus());
        assertNotNull(result.failureReason());
    }

    @Test
    void testNonPositiveAmount_Rejected() {
        RecoveryCase rc = createDetectedCase("SOFT", true, BigDecimal.ZERO, "INR");
        RecoveryDecisionEngine.RecoveryDecision decision = new RecoveryDecisionEngine.RecoveryDecision(
                RecoveryStrategy.SMART_RETRY,
                300,
                "Retry"
        );

        RecoveryGuardrailService.GuardrailResult result = guardrailService.validate(rc, decision);

        assertFalse(result.passed());
        assertEquals(RecoveryStatus.STOPPED, result.targetStatus());
        assertTrue(result.failureReason().contains("greater than zero"));
    }

    @Test
    void testAmountOverMaximum_Escalated() {
        RecoveryCase rc = createDetectedCase("SOFT", true, new BigDecimal("600000.00"), "INR");
        RecoveryDecisionEngine.RecoveryDecision decision = new RecoveryDecisionEngine.RecoveryDecision(
                RecoveryStrategy.SMART_RETRY,
                300,
                "Retry"
        );

        RecoveryGuardrailService.GuardrailResult result = guardrailService.validate(rc, decision);

        assertTrue(result.passed());
        assertEquals(RecoveryStatus.ESCALATED, result.targetStatus());
        assertNull(result.nextActionAt());
        assertTrue(result.failureReason().contains("exceeds configured threshold"));
    }

    @Test
    void testUnsupportedCurrency_Escalated() {
        RecoveryCase rc = createDetectedCase("SOFT", true, new BigDecimal("100.00"), "EUR");
        RecoveryDecisionEngine.RecoveryDecision decision = new RecoveryDecisionEngine.RecoveryDecision(
                RecoveryStrategy.SMART_RETRY,
                300,
                "Retry"
        );

        RecoveryGuardrailService.GuardrailResult result = guardrailService.validate(rc, decision);

        assertTrue(result.passed());
        assertEquals(RecoveryStatus.ESCALATED, result.targetStatus());
        assertNull(result.nextActionAt());
        assertTrue(result.failureReason().contains("Unsupported currency"));
    }

    @Test
    void testUnknownFailure_Escalated() {
        RecoveryCase rc = createDetectedCase("UNKNOWN", true, new BigDecimal("1000.00"), "INR");
        RecoveryDecisionEngine.RecoveryDecision decision = new RecoveryDecisionEngine.RecoveryDecision(
                RecoveryStrategy.MANUAL_ESCALATE,
                0,
                "Unknown anomaly"
        );

        RecoveryGuardrailService.GuardrailResult result = guardrailService.validate(rc, decision);

        assertTrue(result.passed());
        assertEquals(RecoveryStatus.ESCALATED, result.targetStatus());
        assertNull(result.nextActionAt());
    }
}
