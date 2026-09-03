package com.razorrecall.service;

import com.razorrecall.domain.FailureClass;
import com.razorrecall.domain.PaymentAttempt;
import com.razorrecall.domain.RecoveryCase;
import com.razorrecall.domain.RecoveryStatus;
import com.razorrecall.domain.RecoveryStrategy;
import com.razorrecall.dto.AiDiagnosisResult;
import com.razorrecall.dto.FailureContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RecoveryDecisionEngineAiIntegrationTest {

    private RecoveryDecisionEngine engine;
    private RecoveryGuardrailService guardrailService;

    @BeforeEach
    void setUp() {
        guardrailService = new RecoveryGuardrailService(new BigDecimal("500000.00"));
    }

    private RecoveryCase createCase(String failureClass, boolean eligible, String failureReason, BigDecimal amount, String currency) {
        PaymentAttempt attempt = new PaymentAttempt();
        attempt.setId(UUID.randomUUID());
        attempt.setAmount(amount != null ? amount : new BigDecimal("2500.00"));
        attempt.setCurrency(currency != null ? currency : "INR");
        attempt.setFailureReason(failureReason);

        RecoveryCase rc = new RecoveryCase();
        rc.setId(UUID.randomUUID());
        rc.setPaymentAttempt(attempt);
        rc.setFailureClass(failureClass);
        rc.setEligible(eligible);
        rc.setStatus("DETECTED");
        return rc;
    }

    @Test
    void testAiProposesPaymentLink_BackendAcceptsWhenSoftAndHighConfidence() {
        // Mock AI service proposing PAYMENT_LINK with 0.94 confidence
        AiDiagnosisService mockAi = ctx -> new AiDiagnosisResult(
                "3DS authentication drop-off detected by AI",
                FailureClass.SOFT,
                RecoveryStrategy.PAYMENT_LINK,
                0.94,
                List.of("Strong checkout intent", "Recoverable via link"),
                "mock-ai",
                false
        );

        engine = new RecoveryDecisionEngine(mockAi);
        RecoveryCase rc = createCase("SOFT", true, "3DS_AUTH_TIMEOUT", new BigDecimal("2000.00"), "INR");

        RecoveryDecisionEngine.RecoveryDecision decision = engine.decide(rc);
        assertEquals(RecoveryStrategy.PAYMENT_LINK, decision.strategy());
        assertEquals(60, decision.suggestedDelaySeconds());
        assertNotNull(decision.aiDiagnosis());
        assertEquals(0.94, decision.aiDiagnosis().confidence());

        // Guardrails check
        RecoveryGuardrailService.GuardrailResult guardrail = guardrailService.validate(rc, decision);
        assertTrue(guardrail.passed());
        assertEquals(RecoveryStatus.ACTION_PENDING, guardrail.targetStatus());
    }

    @Test
    void testAiProposesLowConfidence_BackendFallsBackToDeterministicLogic() {
        // AI returns confidence 0.45 (< 0.60 threshold)
        AiDiagnosisService mockAi = ctx -> new AiDiagnosisResult(
                "Uncertain diagnosis",
                FailureClass.SOFT,
                RecoveryStrategy.SMART_RETRY,
                0.45,
                List.of("Low signal"),
                "mock-ai",
                false
        );

        engine = new RecoveryDecisionEngine(mockAi);
        RecoveryCase rc = createCase("SOFT", true, "3DS_TIMEOUT", new BigDecimal("1500.00"), "INR");

        RecoveryDecisionEngine.RecoveryDecision decision = engine.decide(rc);
        // Deterministic engine inspects 3DS_TIMEOUT and selects PAYMENT_LINK despite AI suggesting SMART_RETRY
        assertEquals(RecoveryStrategy.PAYMENT_LINK, decision.strategy());
        assertEquals(60, decision.suggestedDelaySeconds());
    }

    @Test
    void testAiProposesUnsafePaymentLinkOnHardFailure_BackendValidationRejectsAndEnforcesAbstain() {
        // Unsafe AI recommendation: proposes PAYMENT_LINK on CARD_EXPIRED
        AiDiagnosisService rogueAi = ctx -> new AiDiagnosisResult(
                "Rogue AI suggesting link on expired card",
                FailureClass.SOFT,
                RecoveryStrategy.PAYMENT_LINK,
                0.99,
                List.of("Hallucinated rationale"),
                "rogue-ai",
                false
        );

        engine = new RecoveryDecisionEngine(rogueAi);
        RecoveryCase rc = createCase("HARD", false, "CARD_EXPIRED", new BigDecimal("1500.00"), "INR");

        RecoveryDecisionEngine.RecoveryDecision decision = engine.decide(rc);
        // Backend validation MUST reject PAYMENT_LINK and enforce ABSTAIN
        assertEquals(RecoveryStrategy.ABSTAIN, decision.strategy());
        assertEquals(0, decision.suggestedDelaySeconds());
        assertTrue(decision.reason().contains("ineligible") || decision.reason().contains("hard failure"));

        // Guardrails also enforce ABSTAINED
        RecoveryGuardrailService.GuardrailResult guardrail = guardrailService.validate(rc, decision);
        assertTrue(guardrail.passed());
        assertEquals(RecoveryStatus.ABSTAINED, guardrail.targetStatus());
    }

    @Test
    void testAiProposesUnsafeStrategyOnUnknownAnomaly_BackendValidationEnforcesManualEscalate() {
        // AI proposes SMART_RETRY on UNKNOWN failure
        AiDiagnosisService rogueAi = ctx -> new AiDiagnosisResult(
                "AI guessing retry on unknown bank code",
                FailureClass.SOFT,
                RecoveryStrategy.SMART_RETRY,
                0.95,
                List.of("Guessing"),
                "rogue-ai",
                false
        );

        engine = new RecoveryDecisionEngine(rogueAi);
        RecoveryCase rc = createCase("UNKNOWN", true, "UNRECOGNIZED_CODE_999", new BigDecimal("1500.00"), "INR");

        RecoveryDecisionEngine.RecoveryDecision decision = engine.decide(rc);
        // Backend validation enforces MANUAL_ESCALATE
        assertEquals(RecoveryStrategy.MANUAL_ESCALATE, decision.strategy());
        assertEquals(0, decision.suggestedDelaySeconds());

        // Guardrails enforce ESCALATED
        RecoveryGuardrailService.GuardrailResult guardrail = guardrailService.validate(rc, decision);
        assertTrue(guardrail.passed());
        assertEquals(RecoveryStatus.ESCALATED, guardrail.targetStatus());
    }

    @Test
    void testGuardrailsRejectAmountOverLimitEvenIfAiProposesValidStrategy() {
        AiDiagnosisService mockAi = ctx -> new AiDiagnosisResult(
                "Valid 3DS diagnosis",
                FailureClass.SOFT,
                RecoveryStrategy.PAYMENT_LINK,
                0.95,
                List.of("High intent"),
                "mock-ai",
                false
        );

        engine = new RecoveryDecisionEngine(mockAi);
        // Amount ₹6,00,000 exceeds ₹5,00,000 threshold
        RecoveryCase rc = createCase("SOFT", true, "3DS_TIMEOUT", new BigDecimal("600000.00"), "INR");

        RecoveryDecisionEngine.RecoveryDecision decision = engine.decide(rc);
        assertEquals(RecoveryStrategy.PAYMENT_LINK, decision.strategy());

        // Guardrails must catch and escalate due to amount threshold rule
        RecoveryGuardrailService.GuardrailResult guardrail = guardrailService.validate(rc, decision);
        assertTrue(guardrail.passed());
        assertEquals(RecoveryStatus.ESCALATED, guardrail.targetStatus());
        assertTrue(guardrail.failureReason().contains("exceeds configured threshold"));
    }
}
