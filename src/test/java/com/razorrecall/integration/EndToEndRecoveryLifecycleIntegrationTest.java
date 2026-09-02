package com.razorrecall.integration;

import com.razorrecall.domain.PaymentAttempt;
import com.razorrecall.domain.RecoveryCase;
import com.razorrecall.domain.RecoveryStatus;
import com.razorrecall.repository.PaymentAttemptRepository;
import com.razorrecall.repository.RecoveryCaseRepository;
import com.razorrecall.service.MerchantWebhookSecretProvider;
import com.razorrecall.service.MockRazorpayGatewayClient;
import com.razorrecall.service.WebhookSignatureVerifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "razorrecall.webhook.secret=test_webhook_secret_key_12345")
@AutoConfigureMockMvc
class EndToEndRecoveryLifecycleIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PaymentAttemptRepository paymentAttemptRepository;

    @Autowired
    private RecoveryCaseRepository recoveryCaseRepository;

    @Autowired
    private WebhookSignatureVerifier signatureVerifier;

    @Autowired
    private MerchantWebhookSecretProvider secretProvider;

    @Autowired
    private MockRazorpayGatewayClient mockRazorpayGatewayClient;

    @AfterEach
    void tearDown() {
        mockRazorpayGatewayClient.setSimulateFailure(false);
        mockRazorpayGatewayClient.resetInvocationCount();
    }

    private String sign(String payload) {
        return signatureVerifier.calculateHmacSha256(payload, secretProvider.getDefaultSecret());
    }

    private RecoveryCase ingestPaymentFailed(String paymentId, String orderId, long amountPaise, String errorCode, String errorReason) throws Exception {
        String payload = """
            {
              "event": "payment.failed",
              "payload": {
                "payment": {
                  "entity": {
                    "id": "%s",
                    "amount": %d,
                    "currency": "INR",
                    "status": "failed",
                    "order_id": "%s",
                    "error_code": "%s",
                    "error_reason": "%s",
                    "error_description": "%s"
                  }
                }
              }
            }
            """.formatted(paymentId, amountPaise, orderId != null ? orderId : "", errorCode, errorReason, errorReason);

        mockMvc.perform(post("/api/webhooks/razorpay")
                .header("X-Razorpay-Signature", sign(payload))
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PROCESSED"));

        PaymentAttempt attempt = paymentAttemptRepository.findByRazorpayPaymentId(paymentId).orElseThrow();
        return recoveryCaseRepository.findByPaymentAttemptId(attempt.getId()).orElseThrow();
    }

    /**
     * Scenario 1:
     * SOFT FAILURE -> PAYMENT_LINK -> CAPTURED -> RECOVERED -> METRICS
     */
    @Test
    void testFullLifecycle_SoftFailure_PaymentLink_Reconciliation_Metrics() throws Exception {
        String paymentId = "pay_plink_flow_" + System.currentTimeMillis();
        String orderId = "order_plink_flow_" + System.currentTimeMillis();
        long amountPaise = 350000; // 3500.00 INR

        // 1. Ingest soft customer-dropoff failure (contains 3ds_auth_timeout)
        RecoveryCase initialCase = ingestPaymentFailed(
                paymentId, orderId, amountPaise, "AUTHENTICATION_TIMEOUT", "3ds_auth_timeout"
        );
        assertEquals(RecoveryStatus.DETECTED.name(), initialCase.getStatus());
        assertTrue(initialCase.isEligible());
        assertEquals("SOFT", initialCase.getFailureClass());

        // 2. Evaluate case -> selects PAYMENT_LINK and transitions to ACTION_PENDING
        mockMvc.perform(post("/api/recovery/cases/" + initialCase.getId() + "/evaluate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTION_PENDING"))
                .andExpect(jsonPath("$.proposedStrategy").value("PAYMENT_LINK"))
                .andExpect(jsonPath("$.eligible").value(true));

        RecoveryCase pendingCase = recoveryCaseRepository.findById(initialCase.getId()).orElseThrow();
        assertEquals(RecoveryStatus.ACTION_PENDING.name(), pendingCase.getStatus());

        // 3. Dispatch recovery action -> creates Payment Link via RazorpayGatewayClient
        mockMvc.perform(post("/api/recovery/cases/" + initialCase.getId() + "/dispatch?force=true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetStatus").value("WAITING_FOR_OUTCOME"))
                .andExpect(jsonPath("$.actionReference", startsWith("plink_")))
                .andExpect(jsonPath("$.actionUrl", startsWith("https://rzp.io/i/")));

        RecoveryCase waitingCase = recoveryCaseRepository.findById(initialCase.getId()).orElseThrow();
        assertEquals(RecoveryStatus.WAITING_FOR_OUTCOME.name(), waitingCase.getStatus());

        // 4. Customer pays via link -> payment.captured webhook with reference_id = caseId arrives
        String capturedPaymentId = "pay_cap_plink_" + System.currentTimeMillis();
        String capPayload = """
            {
              "event": "payment.captured",
              "payload": {
                "payment": {
                  "entity": {
                    "id": "%s",
                    "amount": %d,
                    "currency": "INR",
                    "status": "captured",
                    "notes": {
                      "reference_id": "%s"
                    }
                  }
                }
              }
            }
            """.formatted(capturedPaymentId, amountPaise, initialCase.getId().toString());

        mockMvc.perform(post("/api/webhooks/razorpay")
                .header("X-Razorpay-Signature", sign(capPayload))
                .contentType(MediaType.APPLICATION_JSON)
                .content(capPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RECOVERED"))
                .andExpect(jsonPath("$.recoveryStatus").value("RECOVERED"))
                .andExpect(jsonPath("$.recoveryCaseId").value(initialCase.getId().toString()));

        // 5. Verify database state
        RecoveryCase recoveredCase = recoveryCaseRepository.findById(initialCase.getId()).orElseThrow();
        assertEquals(RecoveryStatus.RECOVERED.name(), recoveredCase.getStatus());

        PaymentAttempt attempt = paymentAttemptRepository.findById(initialCase.getPaymentAttempt().getId()).orElseThrow();
        assertEquals("CAPTURED", attempt.getStatus());
        assertEquals(capturedPaymentId, attempt.getRazorpayPaymentId());
        assertEquals(new BigDecimal("3500.00"), attempt.getAmount());

        // 6. Verify real-time metrics reflects recovery
        mockMvc.perform(get("/api/recovery/metrics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recoveredCases", greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.totalRecoveredAmount", greaterThanOrEqualTo(3500.00)))
                .andExpect(jsonPath("$.recoveryRatePercentage", greaterThan(0.0)));
    }

    /**
     * Scenario 2:
     * SOFT FAILURE -> SMART_RETRY -> ORDER_ID CORRELATION
     */
    @Test
    void testFullLifecycle_SoftFailure_SmartRetry_OrderIdCorrelation_Metrics() throws Exception {
        String paymentId = "pay_retry_flow_" + System.currentTimeMillis();
        String orderId = "order_retry_flow_" + System.currentTimeMillis();
        long amountPaise = 200000; // 2000.00 INR

        // 1. Ingest soft gateway-timeout failure
        RecoveryCase initialCase = ingestPaymentFailed(
                paymentId, orderId, amountPaise, "GATEWAY_TIMEOUT", "gateway_timeout"
        );
        assertEquals(RecoveryStatus.DETECTED.name(), initialCase.getStatus());

        // 2. Evaluate case -> selects SMART_RETRY
        mockMvc.perform(post("/api/recovery/cases/" + initialCase.getId() + "/evaluate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTION_PENDING"))
                .andExpect(jsonPath("$.proposedStrategy").value("SMART_RETRY"));

        // 3. Dispatch action
        mockMvc.perform(post("/api/recovery/cases/" + initialCase.getId() + "/dispatch?force=true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetStatus").value("WAITING_FOR_OUTCOME"));

        // 4. Ingest payment.captured with matching order_id (Priority 1 correlation)
        String capturedPaymentId = "pay_cap_retry_" + System.currentTimeMillis();
        String capPayload = """
            {
              "event": "payment.captured",
              "payload": {
                "payment": {
                  "entity": {
                    "id": "%s",
                    "amount": %d,
                    "currency": "INR",
                    "status": "captured",
                    "order_id": "%s"
                  }
                }
              }
            }
            """.formatted(capturedPaymentId, amountPaise, orderId);

        mockMvc.perform(post("/api/webhooks/razorpay")
                .header("X-Razorpay-Signature", sign(capPayload))
                .contentType(MediaType.APPLICATION_JSON)
                .content(capPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RECOVERED"))
                .andExpect(jsonPath("$.recoveryStatus").value("RECOVERED"));

        RecoveryCase recoveredCase = recoveryCaseRepository.findById(initialCase.getId()).orElseThrow();
        assertEquals(RecoveryStatus.RECOVERED.name(), recoveredCase.getStatus());

        PaymentAttempt attempt = paymentAttemptRepository.findById(initialCase.getPaymentAttempt().getId()).orElseThrow();
        assertEquals("CAPTURED", attempt.getStatus());
        assertEquals(capturedPaymentId, attempt.getRazorpayPaymentId());
    }

    /**
     * Scenario 3:
     * HARD FAILURE -> ABSTAIN -> Dispatch Rejected -> Metrics Intact
     */
    @Test
    void testFullLifecycle_HardFailure_AbstainsAndPreservesTerminalState() throws Exception {
        String paymentId = "pay_hard_flow_" + System.currentTimeMillis();
        String orderId = "order_hard_flow_" + System.currentTimeMillis();

        // 1. Ingest hard terminal failure
        RecoveryCase initialCase = ingestPaymentFailed(
                paymentId, orderId, 150000, "INSUFFICIENT_FUNDS", "payment_failed_insufficient_balance"
        );
        assertEquals(RecoveryStatus.DETECTED.name(), initialCase.getStatus());
        assertFalse(initialCase.isEligible());
        assertEquals("HARD", initialCase.getFailureClass());

        // 2. Evaluate case -> decision is ABSTAIN, target status is ABSTAINED
        mockMvc.perform(post("/api/recovery/cases/" + initialCase.getId() + "/evaluate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ABSTAINED"))
                .andExpect(jsonPath("$.proposedStrategy").value("ABSTAIN"))
                .andExpect(jsonPath("$.eligible").value(false));

        RecoveryCase abstainedCase = recoveryCaseRepository.findById(initialCase.getId()).orElseThrow();
        assertEquals(RecoveryStatus.ABSTAINED.name(), abstainedCase.getStatus());

        // 3. Attempting to dispatch an ABSTAINED case must be rejected by guardrails (HTTP 400)
        mockMvc.perform(post("/api/recovery/cases/" + initialCase.getId() + "/dispatch?force=true"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("Cannot dispatch case in status 'ABSTAINED'")));

        // 4. Metrics reflects abstained count
        mockMvc.perform(get("/api/recovery/metrics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.abstainedCases", greaterThanOrEqualTo(1)));
    }

    /**
     * Scenario 4:
     * UNKNOWN FAILURE -> MANUAL_ESCALATE -> Dispatch Rejected -> Metrics Intact
     */
    @Test
    void testFullLifecycle_UnknownFailure_EscalatesForManualReview() throws Exception {
        String paymentId = "pay_unk_flow_" + System.currentTimeMillis();
        String orderId = "order_unk_flow_" + System.currentTimeMillis();

        // 1. Ingest unclassified / unknown error
        RecoveryCase initialCase = ingestPaymentFailed(
                paymentId, orderId, 100000, "CUSTOM_ERR_UNKNOWN_XYZ", "unmapped_bank_fault"
        );
        assertEquals(RecoveryStatus.DETECTED.name(), initialCase.getStatus());
        assertEquals("UNKNOWN", initialCase.getFailureClass());

        // Enable eligibility to exercise Rule 5 (MANUAL_ESCALATE anomaly guardrail)
        initialCase.setEligible(true);
        recoveryCaseRepository.save(initialCase);

        // 2. Evaluate case -> decision is MANUAL_ESCALATE, status is ESCALATED
        mockMvc.perform(post("/api/recovery/cases/" + initialCase.getId() + "/evaluate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ESCALATED"))
                .andExpect(jsonPath("$.proposedStrategy").value("MANUAL_ESCALATE"));

        RecoveryCase escalatedCase = recoveryCaseRepository.findById(initialCase.getId()).orElseThrow();
        assertEquals(RecoveryStatus.ESCALATED.name(), escalatedCase.getStatus());

        // 3. Attempting dispatch on ESCALATED case is rejected
        mockMvc.perform(post("/api/recovery/cases/" + initialCase.getId() + "/dispatch?force=true"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("Cannot dispatch case in status 'ESCALATED'")));

        // 4. Metrics reflects escalated count
        mockMvc.perform(get("/api/recovery/metrics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.escalatedCases", greaterThanOrEqualTo(1)));
    }

    /**
     * Scenario 5:
     * GATEWAY FAILURE -> ACTION_FAILED -> Repeated Dispatch Rejected
     */
    @Test
    void testFullLifecycle_GatewayOutage_ActionFailedAndMetrics() throws Exception {
        String paymentId = "pay_gw_fail_" + System.currentTimeMillis();
        String orderId = "order_gw_fail_" + System.currentTimeMillis();

        // 1. Ingest soft failure with 3DS timeout reason so PAYMENT_LINK strategy is selected
        RecoveryCase initialCase = ingestPaymentFailed(
                paymentId, orderId, 250000, "AUTHENTICATION_TIMEOUT", "3ds_auth_timeout"
        );
        mockMvc.perform(post("/api/recovery/cases/" + initialCase.getId() + "/evaluate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTION_PENDING"))
                .andExpect(jsonPath("$.proposedStrategy").value("PAYMENT_LINK"));

        // 2. Simulate gateway outage
        mockRazorpayGatewayClient.setSimulateFailure(true);

        // 3. Dispatch fails -> HTTP 502 Bad Gateway
        mockMvc.perform(post("/api/recovery/cases/" + initialCase.getId() + "/dispatch?force=true"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.status").value("ACTION_FAILED"));

        RecoveryCase failedCase = recoveryCaseRepository.findById(initialCase.getId()).orElseThrow();
        assertEquals(RecoveryStatus.ACTION_FAILED.name(), failedCase.getStatus());

        int countBefore = mockRazorpayGatewayClient.getInvocationCount();

        // 4. Repeated dispatch on ACTION_FAILED is rejected by state guardrail without hitting gateway
        mockMvc.perform(post("/api/recovery/cases/" + initialCase.getId() + "/dispatch?force=true"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("ACTION_PENDING")));

        assertEquals(countBefore, mockRazorpayGatewayClient.getInvocationCount());

        // 5. Metrics reflects failed count
        mockMvc.perform(get("/api/recovery/metrics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failedCases", greaterThanOrEqualTo(1)));
    }

    /**
     * Scenario 6:
     * BATCH PIPELINE: evaluate-detected -> dispatch-due -> captured webhooks -> RECOVERED
     */
    @Test
    void testFullLifecycle_BatchPipeline_EvaluateDetectedAndDispatchDue() throws Exception {
        String p1 = "pay_batch_1_" + System.currentTimeMillis();
        String o1 = "order_batch_1_" + System.currentTimeMillis();
        String p2 = "pay_batch_2_" + System.currentTimeMillis();
        String o2 = "order_batch_2_" + System.currentTimeMillis();

        // 1. Ingest two soft failures
        RecoveryCase c1 = ingestPaymentFailed(p1, o1, 200000, "GATEWAY_TIMEOUT", "gateway_timeout");
        RecoveryCase c2 = ingestPaymentFailed(p2, o2, 300000, "GATEWAY_TIMEOUT", "gateway_timeout");

        assertEquals(RecoveryStatus.DETECTED.name(), c1.getStatus());
        assertEquals(RecoveryStatus.DETECTED.name(), c2.getStatus());

        // 2. Batch evaluate all DETECTED cases via POST /api/recovery/cases/evaluate-detected
        mockMvc.perform(post("/api/recovery/cases/evaluate-detected"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(greaterThanOrEqualTo(2))))
                .andExpect(jsonPath("$[*].status", hasItems("ACTION_PENDING")));

        // 3. Fast-forward nextActionAt so cases are eligible for dispatch-due
        RecoveryCase eval1 = recoveryCaseRepository.findById(c1.getId()).orElseThrow();
        eval1.setNextActionAt(OffsetDateTime.now().minusMinutes(5));
        recoveryCaseRepository.save(eval1);

        RecoveryCase eval2 = recoveryCaseRepository.findById(c2.getId()).orElseThrow();
        eval2.setNextActionAt(OffsetDateTime.now().minusMinutes(5));
        recoveryCaseRepository.save(eval2);

        // 4. Batch dispatch due cases via POST /api/recovery/cases/dispatch-due
        mockMvc.perform(post("/api/recovery/cases/dispatch-due"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(greaterThanOrEqualTo(2))))
                .andExpect(jsonPath("$[*].targetStatus", hasItems("WAITING_FOR_OUTCOME")));

        RecoveryCase disp1 = recoveryCaseRepository.findById(c1.getId()).orElseThrow();
        assertEquals(RecoveryStatus.WAITING_FOR_OUTCOME.name(), disp1.getStatus());

        RecoveryCase disp2 = recoveryCaseRepository.findById(c2.getId()).orElseThrow();
        assertEquals(RecoveryStatus.WAITING_FOR_OUTCOME.name(), disp2.getStatus());

        // 5. Ingest payment.captured webhooks for both cases
        String cap1 = """
            {
              "event": "payment.captured",
              "payload": {
                "payment": {
                  "entity": {
                    "id": "pay_cap_batch_1_%d",
                    "amount": 200000,
                    "currency": "INR",
                    "status": "captured",
                    "order_id": "%s"
                  }
                }
              }
            }
            """.formatted(System.currentTimeMillis(), o1);

        String cap2 = """
            {
              "event": "payment.captured",
              "payload": {
                "payment": {
                  "entity": {
                    "id": "pay_cap_batch_2_%d",
                    "amount": 300000,
                    "currency": "INR",
                    "status": "captured",
                    "order_id": "%s"
                  }
                }
              }
            }
            """.formatted(System.currentTimeMillis(), o2);

        mockMvc.perform(post("/api/webhooks/razorpay")
                .header("X-Razorpay-Signature", sign(cap1))
                .contentType(MediaType.APPLICATION_JSON)
                .content(cap1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RECOVERED"));

        mockMvc.perform(post("/api/webhooks/razorpay")
                .header("X-Razorpay-Signature", sign(cap2))
                .contentType(MediaType.APPLICATION_JSON)
                .content(cap2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RECOVERED"));

        assertEquals(RecoveryStatus.RECOVERED.name(), recoveryCaseRepository.findById(c1.getId()).orElseThrow().getStatus());
        assertEquals(RecoveryStatus.RECOVERED.name(), recoveryCaseRepository.findById(c2.getId()).orElseThrow().getStatus());
    }

    /**
     * Scenario 7:
     * THREE-TIER CORRELATION:
     * 1. order_id
     * 2. reference_id
     * 3. payment_id
     */
    @Test
    void testFullLifecycle_ThreeTierCorrelationPriorities() throws Exception {
        // Priority 1: order_id match takes precedence
        String p1 = "pay_p1_" + System.currentTimeMillis();
        String o1 = "order_p1_" + System.currentTimeMillis();
        RecoveryCase c1 = ingestPaymentFailed(p1, o1, 100000, "GATEWAY_TIMEOUT", "gateway_timeout");
        mockMvc.perform(post("/api/recovery/cases/" + c1.getId() + "/evaluate"));
        mockMvc.perform(post("/api/recovery/cases/" + c1.getId() + "/dispatch?force=true"));

        String cap1 = """
            {
              "event": "payment.captured",
              "payload": {
                "payment": {
                  "entity": {
                    "id": "pay_cap_p1_%d",
                    "amount": 100000,
                    "currency": "INR",
                    "status": "captured",
                    "order_id": "%s"
                  }
                }
              }
            }
            """.formatted(System.currentTimeMillis(), o1);

        mockMvc.perform(post("/api/webhooks/razorpay")
                .header("X-Razorpay-Signature", sign(cap1))
                .contentType(MediaType.APPLICATION_JSON)
                .content(cap1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RECOVERED"))
                .andExpect(jsonPath("$.recoveryCaseId").value(c1.getId().toString()));

        // Priority 2: reference_id match when order_id does not match
        String p2 = "pay_p2_" + System.currentTimeMillis();
        String o2 = "order_p2_" + System.currentTimeMillis();
        RecoveryCase c2 = ingestPaymentFailed(p2, o2, 150000, "AUTHENTICATION_TIMEOUT", "3ds_auth_timeout");
        mockMvc.perform(post("/api/recovery/cases/" + c2.getId() + "/evaluate"));
        mockMvc.perform(post("/api/recovery/cases/" + c2.getId() + "/dispatch?force=true"));

        String cap2 = """
            {
              "event": "payment.captured",
              "payload": {
                "payment": {
                  "entity": {
                    "id": "pay_cap_p2_%d",
                    "amount": 150000,
                    "currency": "INR",
                    "status": "captured",
                    "order_id": "different_unrelated_order",
                    "notes": {
                      "reference_id": "%s"
                    }
                  }
                }
              }
            }
            """.formatted(System.currentTimeMillis(), c2.getId().toString());

        mockMvc.perform(post("/api/webhooks/razorpay")
                .header("X-Razorpay-Signature", sign(cap2))
                .contentType(MediaType.APPLICATION_JSON)
                .content(cap2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RECOVERED"))
                .andExpect(jsonPath("$.recoveryCaseId").value(c2.getId().toString()));

        // Priority 3: payment_id fallback when order_id and reference_id are absent
        String p3 = "pay_p3_" + System.currentTimeMillis();
        RecoveryCase c3 = ingestPaymentFailed(p3, null, 120000, "GATEWAY_TIMEOUT", "gateway_timeout");
        mockMvc.perform(post("/api/recovery/cases/" + c3.getId() + "/evaluate"));
        mockMvc.perform(post("/api/recovery/cases/" + c3.getId() + "/dispatch?force=true"));

        String cap3 = """
            {
              "event": "payment.captured",
              "payload": {
                "payment": {
                  "entity": {
                    "id": "%s",
                    "amount": 120000,
                    "currency": "INR",
                    "status": "captured"
                  }
                }
              }
            }
            """.formatted(p3);

        mockMvc.perform(post("/api/webhooks/razorpay")
                .header("X-Razorpay-Signature", sign(cap3))
                .contentType(MediaType.APPLICATION_JSON)
                .content(cap3))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RECOVERED"))
                .andExpect(jsonPath("$.recoveryCaseId").value(c3.getId().toString()));
    }

    /**
     * Scenario 8:
     * SECURITY + IDEMPOTENCY:
     * - invalid signature rejected (400 Bad Request)
     * - duplicate payment.failed idempotent (200 DUPLICATE)
     * - duplicate payment.captured idempotent (200 DUPLICATE)
     * - repeated dispatch does not execute gateway twice
     */
    @Test
    void testFullLifecycle_SecurityAndIdempotency() throws Exception {
        String paymentId = "pay_sec_idem_" + System.currentTimeMillis();
        String orderId = "order_sec_idem_" + System.currentTimeMillis();

        String failPayload = """
            {
              "event": "payment.failed",
              "payload": {
                "payment": {
                  "entity": {
                    "id": "%s",
                    "amount": 100000,
                    "currency": "INR",
                    "status": "failed",
                    "order_id": "%s",
                    "error_code": "GATEWAY_TIMEOUT",
                    "error_reason": "gateway_timeout",
                    "error_description": "gateway_timeout"
                  }
                }
              }
            }
            """.formatted(paymentId, orderId);

        // 1. Invalid signature rejected with HTTP 400 Bad Request
        mockMvc.perform(post("/api/webhooks/razorpay")
                .header("X-Razorpay-Signature", "invalid_forged_hmac_signature")
                .contentType(MediaType.APPLICATION_JSON)
                .content(failPayload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Invalid webhook signature"));

        // 2. Valid signature accepted
        mockMvc.perform(post("/api/webhooks/razorpay")
                .header("X-Razorpay-Signature", sign(failPayload))
                .contentType(MediaType.APPLICATION_JSON)
                .content(failPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PROCESSED"));

        // 3. Duplicate payment.failed is idempotent
        mockMvc.perform(post("/api/webhooks/razorpay")
                .header("X-Razorpay-Signature", sign(failPayload))
                .contentType(MediaType.APPLICATION_JSON)
                .content(failPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DUPLICATE"));

        PaymentAttempt attempt = paymentAttemptRepository.findByRazorpayPaymentId(paymentId).orElseThrow();
        RecoveryCase recoveryCase = recoveryCaseRepository.findByPaymentAttemptId(attempt.getId()).orElseThrow();

        // 4. Evaluate and dispatch
        mockMvc.perform(post("/api/recovery/cases/" + recoveryCase.getId() + "/evaluate"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/recovery/cases/" + recoveryCase.getId() + "/dispatch?force=true"))
                .andExpect(status().isOk());

        int invocationCountAfterFirstDispatch = mockRazorpayGatewayClient.getInvocationCount();

        // 5. Repeated dispatch is rejected and does not execute gateway twice
        mockMvc.perform(post("/api/recovery/cases/" + recoveryCase.getId() + "/dispatch?force=true"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("ACTION_PENDING")));

        assertEquals(invocationCountAfterFirstDispatch, mockRazorpayGatewayClient.getInvocationCount());

        // 6. Ingest payment.captured
        String capId = "pay_cap_sec_" + System.currentTimeMillis();
        String capPayload = """
            {
              "event": "payment.captured",
              "payload": {
                "payment": {
                  "entity": {
                    "id": "%s",
                    "amount": 100000,
                    "currency": "INR",
                    "status": "captured",
                    "order_id": "%s"
                  }
                }
              }
            }
            """.formatted(capId, orderId);

        mockMvc.perform(post("/api/webhooks/razorpay")
                .header("X-Razorpay-Signature", sign(capPayload))
                .contentType(MediaType.APPLICATION_JSON)
                .content(capPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RECOVERED"));

        // 7. Duplicate payment.captured is idempotent and does not double-count
        mockMvc.perform(post("/api/webhooks/razorpay")
                .header("X-Razorpay-Signature", sign(capPayload))
                .contentType(MediaType.APPLICATION_JSON)
                .content(capPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DUPLICATE"))
                .andExpect(jsonPath("$.recoveryStatus").value("RECOVERED"));
    }
}
