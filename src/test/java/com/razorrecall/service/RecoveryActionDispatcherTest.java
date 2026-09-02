package com.razorrecall.service;

import com.razorrecall.domain.PaymentAttempt;
import com.razorrecall.domain.RecoveryCase;
import com.razorrecall.domain.RecoveryStatus;
import com.razorrecall.domain.RecoveryStrategy;
import com.razorrecall.dto.ActionExecutionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RecoveryActionDispatcherTest {

    private RecoveryActionDispatcher dispatcher;
    private RazorpayGatewayClient gatewayClient;

    @BeforeEach
    void setUp() {
        gatewayClient = new MockRazorpayGatewayClient();
        dispatcher = new RecoveryActionDispatcher(gatewayClient);
    }

    private RecoveryCase createActionPendingCase(BigDecimal amount, String currency) {
        PaymentAttempt attempt = new PaymentAttempt();
        attempt.setId(UUID.randomUUID());
        attempt.setOrderId("order_test_999");
        attempt.setAmount(amount);
        attempt.setCurrency(currency);
        attempt.setStatus("FAILED");

        RecoveryCase rc = new RecoveryCase();
        rc.setId(UUID.randomUUID());
        rc.setPaymentAttempt(attempt);
        rc.setStatus(RecoveryStatus.ACTION_PENDING.name());
        rc.setEligible(true);
        rc.setFailureClass("SOFT");
        return rc;
    }

    @Test
    void testDispatchSmartRetry() {
        RecoveryCase rc = createActionPendingCase(new BigDecimal("1500.00"), "INR");
        ActionExecutionResult result = dispatcher.dispatch(rc, RecoveryStrategy.SMART_RETRY);

        assertNotNull(result);
        assertEquals(rc.getId(), result.recoveryCaseId());
        assertEquals(RecoveryStrategy.SMART_RETRY, result.strategy());
        assertEquals(RecoveryStatus.WAITING_FOR_OUTCOME, result.targetStatus());
        assertNotNull(result.actionReference());
        assertTrue(result.actionReference().startsWith("retry_"));
        assertNull(result.actionUrl());
        assertTrue(result.message().contains("smart retry"));
    }

    @Test
    void testDispatchPaymentLink() {
        RecoveryCase rc = createActionPendingCase(new BigDecimal("2500.00"), "INR");
        ActionExecutionResult result = dispatcher.dispatch(rc, RecoveryStrategy.PAYMENT_LINK);

        assertNotNull(result);
        assertEquals(rc.getId(), result.recoveryCaseId());
        assertEquals(RecoveryStrategy.PAYMENT_LINK, result.strategy());
        assertEquals(RecoveryStatus.WAITING_FOR_OUTCOME, result.targetStatus());
        assertNotNull(result.actionReference());
        assertTrue(result.actionReference().startsWith("plink_"));
        assertNotNull(result.actionUrl());
        assertTrue(result.actionUrl().startsWith("https://rzp.io/i/"));
    }

    @Test
    void testDispatchCustomerNudge() {
        RecoveryCase rc = createActionPendingCase(new BigDecimal("3500.00"), "INR");
        ActionExecutionResult result = dispatcher.dispatch(rc, RecoveryStrategy.CUSTOMER_NUDGE);

        assertNotNull(result);
        assertEquals(rc.getId(), result.recoveryCaseId());
        assertEquals(RecoveryStrategy.CUSTOMER_NUDGE, result.strategy());
        assertEquals(RecoveryStatus.WAITING_FOR_OUTCOME, result.targetStatus());
        assertNotNull(result.actionReference());
        assertTrue(result.actionReference().startsWith("plink_"));
        assertNotNull(result.actionUrl());
        assertTrue(result.message().contains("nudge"));
    }

    @Test
    void testDispatchAbstain_ThrowsException() {
        RecoveryCase rc = createActionPendingCase(new BigDecimal("100.00"), "INR");
        assertThrows(IllegalArgumentException.class, () -> dispatcher.dispatch(rc, RecoveryStrategy.ABSTAIN));
    }

    @Test
    void testDispatchManualEscalate_ThrowsException() {
        RecoveryCase rc = createActionPendingCase(new BigDecimal("100.00"), "INR");
        assertThrows(IllegalArgumentException.class, () -> dispatcher.dispatch(rc, RecoveryStrategy.MANUAL_ESCALATE));
    }

    @Test
    void testDispatchMissingPaymentAttempt_ThrowsException() {
        RecoveryCase rc = new RecoveryCase();
        rc.setId(UUID.randomUUID());
        rc.setStatus(RecoveryStatus.ACTION_PENDING.name());
        assertThrows(IllegalStateException.class, () -> dispatcher.dispatch(rc, RecoveryStrategy.SMART_RETRY));
    }
}
