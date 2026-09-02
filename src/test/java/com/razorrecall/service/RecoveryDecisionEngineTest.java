package com.razorrecall.service;

import com.razorrecall.domain.PaymentAttempt;
import com.razorrecall.domain.RecoveryCase;
import com.razorrecall.domain.RecoveryStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RecoveryDecisionEngineTest {

    private RecoveryDecisionEngine engine;

    @BeforeEach
    void setUp() {
        engine = new RecoveryDecisionEngine();
    }

    private RecoveryCase createCase(String failureClass, boolean eligible, String failureReason) {
        PaymentAttempt attempt = new PaymentAttempt();
        attempt.setId(UUID.randomUUID());
        attempt.setAmount(new BigDecimal("1500.00"));
        attempt.setCurrency("INR");
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
    void testTransientGatewayTimeoutSelectsSmartRetry() {
        RecoveryCase rc = createCase("SOFT", true, "gateway_timeout");
        RecoveryDecisionEngine.RecoveryDecision decision = engine.decide(rc);

        assertEquals(RecoveryStrategy.SMART_RETRY, decision.strategy());
        assertEquals(300, decision.suggestedDelaySeconds());
        assertNotNull(decision.reason());
    }

    @Test
    void testCustomerDropout3DSSelectsPaymentLink() {
        RecoveryCase rc = createCase("SOFT", true, "3ds_auth_timeout");
        RecoveryDecisionEngine.RecoveryDecision decision = engine.decide(rc);

        assertEquals(RecoveryStrategy.PAYMENT_LINK, decision.strategy());
        assertEquals(60, decision.suggestedDelaySeconds());
        assertNotNull(decision.reason());
    }

    @Test
    void testRecoverableOtpCustomerPromptSelectsCustomerNudge() {
        RecoveryCase rc = createCase("SOFT", true, "incorrect_otp_entered");
        RecoveryDecisionEngine.RecoveryDecision decision = engine.decide(rc);

        assertEquals(RecoveryStrategy.CUSTOMER_NUDGE, decision.strategy());
        assertEquals(180, decision.suggestedDelaySeconds());
        assertNotNull(decision.reason());
    }

    @Test
    void testHardFailureSelectsAbstain() {
        RecoveryCase rc = createCase("HARD", false, "card_expired");
        RecoveryDecisionEngine.RecoveryDecision decision = engine.decide(rc);

        assertEquals(RecoveryStrategy.ABSTAIN, decision.strategy());
        assertEquals(0, decision.suggestedDelaySeconds());
        assertNotNull(decision.reason());
    }

    @Test
    void testUnknownFailureSelectsManualEscalate() {
        RecoveryCase rc = createCase("UNKNOWN", true, "unrecognized_bank_code_xyz");
        RecoveryDecisionEngine.RecoveryDecision decision = engine.decide(rc);

        assertEquals(RecoveryStrategy.MANUAL_ESCALATE, decision.strategy());
        assertEquals(0, decision.suggestedDelaySeconds());
        assertNotNull(decision.reason());
    }

    @Test
    void testDeterministicDelayAndReasonBehavior() {
        RecoveryCase rc1 = createCase("SOFT", true, "gateway_error");
        RecoveryDecisionEngine.RecoveryDecision decision1 = engine.decide(rc1);

        RecoveryCase rc2 = createCase("SOFT", true, "gateway_error");
        RecoveryDecisionEngine.RecoveryDecision decision2 = engine.decide(rc2);

        assertEquals(decision1.strategy(), decision2.strategy());
        assertEquals(decision1.suggestedDelaySeconds(), decision2.suggestedDelaySeconds());
        assertEquals(decision1.reason(), decision2.reason());
    }
}
