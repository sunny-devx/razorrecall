package com.razorrecall.controller;

import com.razorrecall.domain.PaymentAttempt;
import com.razorrecall.domain.RecoveryCase;
import com.razorrecall.domain.RecoveryStatus;
import com.razorrecall.repository.PaymentAttemptRepository;
import com.razorrecall.repository.RecoveryCaseRepository;
import com.razorrecall.service.MerchantWebhookSecretProvider;
import com.razorrecall.service.MockRazorpayGatewayClient;
import com.razorrecall.service.WebhookSignatureVerifier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "razorrecall.webhook.secret=test_webhook_secret_key_12345")
@AutoConfigureMockMvc
class RecoveryCaseControllerTest {

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

    private RecoveryCase ingestWebhook(String paymentId, String errorCode, String errorReason, String errorDescription) throws Exception {
        String jsonPayload = """
            {
              "event": "payment.failed",
              "payload": {
                "payment": {
                  "entity": {
                    "id": "%s",
                    "amount": 250000,
                    "currency": "INR",
                    "status": "failed",
                    "error_code": "%s",
                    "error_reason": "%s",
                    "error_description": "%s"
                  }
                }
              }
            }
            """.formatted(paymentId, errorCode, errorReason, errorDescription);

        String secret = secretProvider.getDefaultSecret();
        String signature = signatureVerifier.calculateHmacSha256(jsonPayload, secret);

        mockMvc.perform(post("/api/webhooks/razorpay")
                .header("X-Razorpay-Signature", signature)
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonPayload))
                .andExpect(status().isOk());

        Optional<PaymentAttempt> attemptOpt = paymentAttemptRepository.findByRazorpayPaymentId(paymentId);
        assertTrue(attemptOpt.isPresent());

        Optional<RecoveryCase> caseOpt = recoveryCaseRepository.findByPaymentAttemptId(attemptOpt.get().getId());
        assertTrue(caseOpt.isPresent());
        return caseOpt.get();
    }

    @Test
    void testEvaluateValidDetectedCase_ActionPending() throws Exception {
        String paymentId = "pay_ctrl_valid_" + System.currentTimeMillis();
        RecoveryCase initialCase = ingestWebhook(paymentId, "GATEWAY_ERROR", "gateway_timeout", "Temporary gateway issue");

        assertEquals(RecoveryStatus.DETECTED.name(), initialCase.getStatus());

        mockMvc.perform(post("/api/recovery/cases/" + initialCase.getId() + "/evaluate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTION_PENDING"))
                .andExpect(jsonPath("$.proposedStrategy").value("SMART_RETRY"))
                .andExpect(jsonPath("$.eligible").value(true));

        RecoveryCase updated = recoveryCaseRepository.findById(initialCase.getId()).orElseThrow();
        assertEquals(RecoveryStatus.ACTION_PENDING.name(), updated.getStatus());
    }

    @Test
    void testVerifyNextActionAtPopulatedOnEvaluation() throws Exception {
        String paymentId = "pay_ctrl_sched_" + System.currentTimeMillis();
        RecoveryCase initialCase = ingestWebhook(paymentId, "GATEWAY_ERROR", "gateway_timeout", "Temporary gateway issue");

        mockMvc.perform(post("/api/recovery/cases/" + initialCase.getId() + "/evaluate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nextActionAt").isNotEmpty());

        RecoveryCase updated = recoveryCaseRepository.findById(initialCase.getId()).orElseThrow();
        assertNotNull(updated.getNextActionAt());
    }

    @Test
    void testIneligibleCase_Abstained() throws Exception {
        String paymentId = "pay_ctrl_ineligible_" + System.currentTimeMillis();
        RecoveryCase initialCase = ingestWebhook(paymentId, "CARD_EXPIRED", "card_expired", "Card is expired");

        mockMvc.perform(post("/api/recovery/cases/" + initialCase.getId() + "/evaluate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ABSTAINED"))
                .andExpect(jsonPath("$.proposedStrategy").value("ABSTAIN"))
                .andExpect(jsonPath("$.eligible").value(false))
                .andExpect(jsonPath("$.nextActionAt").isEmpty());

        RecoveryCase updated = recoveryCaseRepository.findById(initialCase.getId()).orElseThrow();
        assertEquals(RecoveryStatus.ABSTAINED.name(), updated.getStatus());
        assertNull(updated.getNextActionAt());
    }

    @Test
    void testGetCaseReturnsPaymentAndRecoveryInformation() throws Exception {
        String paymentId = "pay_ctrl_get_" + System.currentTimeMillis();
        RecoveryCase initialCase = ingestWebhook(paymentId, "GATEWAY_ERROR", "timeout", "Gateway timeout");

        mockMvc.perform(get("/api/recovery/cases/" + initialCase.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(initialCase.getId().toString()))
                .andExpect(jsonPath("$.razorpayPaymentId").value(paymentId))
                .andExpect(jsonPath("$.status").value("DETECTED"))
                .andExpect(jsonPath("$.currency").value("INR"))
                .andExpect(jsonPath("$.eligible").value(true));
    }

    @Test
    void testMissingCaseReturns404() throws Exception {
        UUID nonExistentId = UUID.randomUUID();
        mockMvc.perform(get("/api/recovery/cases/" + nonExistentId))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/recovery/cases/" + nonExistentId + "/evaluate"))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/recovery/cases/" + nonExistentId + "/dispatch"))
                .andExpect(status().isNotFound());
    }

    @Test
    void testListAndFilterEndpointWorks() throws Exception {
        String paymentId = "pay_ctrl_list_" + System.currentTimeMillis();
        RecoveryCase created = ingestWebhook(paymentId, "GATEWAY_ERROR", "timeout", "Gateway timeout");

        mockMvc.perform(get("/api/recovery/cases?status=DETECTED&eligible=true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void testDispatchAction_PendingCase_Success() throws Exception {
        String paymentId = "pay_ctrl_disp_" + System.currentTimeMillis();
        RecoveryCase initialCase = ingestWebhook(paymentId, "GATEWAY_ERROR", "3ds_auth_timeout", "User dropped off at 3DS");

        // First evaluate
        mockMvc.perform(post("/api/recovery/cases/" + initialCase.getId() + "/evaluate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTION_PENDING"))
                .andExpect(jsonPath("$.proposedStrategy").value("PAYMENT_LINK"));

        // Dispatch with force=true to bypass cooldown
        mockMvc.perform(post("/api/recovery/cases/" + initialCase.getId() + "/dispatch?force=true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetStatus").value("WAITING_FOR_OUTCOME"))
                .andExpect(jsonPath("$.actionReference").isNotEmpty())
                .andExpect(jsonPath("$.actionUrl").isNotEmpty());

        RecoveryCase updated = recoveryCaseRepository.findById(initialCase.getId()).orElseThrow();
        assertEquals(RecoveryStatus.WAITING_FOR_OUTCOME.name(), updated.getStatus());
    }

    @Test
    void testDispatchAction_Idempotency_SecondDispatchRejected() throws Exception {
        String paymentId = "pay_ctrl_idem_" + System.currentTimeMillis();
        RecoveryCase initialCase = ingestWebhook(paymentId, "GATEWAY_ERROR", "gateway_timeout", "Transient error");

        mockMvc.perform(post("/api/recovery/cases/" + initialCase.getId() + "/evaluate"))
                .andExpect(status().isOk());

        // First dispatch succeeds
        mockMvc.perform(post("/api/recovery/cases/" + initialCase.getId() + "/dispatch?force=true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetStatus").value("WAITING_FOR_OUTCOME"));

        // Second dispatch must fail because status is now WAITING_FOR_OUTCOME
        mockMvc.perform(post("/api/recovery/cases/" + initialCase.getId() + "/dispatch?force=true"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    @Test
    void testDispatchAction_TimingGuardrail_WithoutForce_Rejected() throws Exception {
        String paymentId = "pay_ctrl_time_" + System.currentTimeMillis();
        RecoveryCase initialCase = ingestWebhook(paymentId, "GATEWAY_ERROR", "gateway_timeout", "Transient error");

        mockMvc.perform(post("/api/recovery/cases/" + initialCase.getId() + "/evaluate"))
                .andExpect(status().isOk());

        // Dispatch without force=true must be rejected by timing cooldown guardrail
        mockMvc.perform(post("/api/recovery/cases/" + initialCase.getId() + "/dispatch"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("Timing guardrail")));
    }

    @Test
    void testDispatchAction_DetectedCase_Rejected() throws Exception {
        String paymentId = "pay_ctrl_detected_" + System.currentTimeMillis();
        RecoveryCase initialCase = ingestWebhook(paymentId, "GATEWAY_ERROR", "gateway_timeout", "Transient error");

        assertEquals(RecoveryStatus.DETECTED.name(), initialCase.getStatus());

        // Dispatch directly on DETECTED case without evaluate must fail
        mockMvc.perform(post("/api/recovery/cases/" + initialCase.getId() + "/dispatch?force=true"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("Case must be in ACTION_PENDING")));
    }

    @Test
    void testDispatchAction_NonActionableStrategy_Rejected() throws Exception {
        String paymentId = "pay_ctrl_ineligible_disp_" + System.currentTimeMillis();
        RecoveryCase initialCase = ingestWebhook(paymentId, "CARD_EXPIRED", "card_expired", "Card is expired");

        mockMvc.perform(post("/api/recovery/cases/" + initialCase.getId() + "/evaluate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ABSTAINED"));

        mockMvc.perform(post("/api/recovery/cases/" + initialCase.getId() + "/dispatch?force=true"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void testDispatchDueCases_BatchEndpoint() throws Exception {
        String paymentId = "pay_ctrl_due_" + System.currentTimeMillis();
        RecoveryCase initialCase = ingestWebhook(paymentId, "GATEWAY_ERROR", "gateway_timeout", "Transient error");

        mockMvc.perform(post("/api/recovery/cases/" + initialCase.getId() + "/evaluate"))
                .andExpect(status().isOk());

        // Manually mature nextActionAt to the past
        RecoveryCase pendingCase = recoveryCaseRepository.findById(initialCase.getId()).orElseThrow();
        pendingCase.setNextActionAt(OffsetDateTime.now().minusMinutes(1));
        recoveryCaseRepository.save(pendingCase);

        // Call dispatch-due
        mockMvc.perform(post("/api/recovery/cases/dispatch-due"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());

        RecoveryCase postDispatch = recoveryCaseRepository.findById(initialCase.getId()).orElseThrow();
        assertEquals(RecoveryStatus.WAITING_FOR_OUTCOME.name(), postDispatch.getStatus());
    }

    @Test
    void testDispatchAction_GatewayFailure_TransitionsToAndPersistsActionFailed() throws Exception {
        String paymentId = "pay_ctrl_fail_" + System.currentTimeMillis();
        RecoveryCase initialCase = ingestWebhook(paymentId, "GATEWAY_ERROR", "3ds_auth_timeout", "Dropoff at 3DS");

        mockMvc.perform(post("/api/recovery/cases/" + initialCase.getId() + "/evaluate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTION_PENDING"));

        RecoveryCase pendingCase = recoveryCaseRepository.findById(initialCase.getId()).orElseThrow();
        OffsetDateTime preDispatchUpdatedAt = pendingCase.getUpdatedAt();

        mockRazorpayGatewayClient.resetInvocationCount();
        try {
            mockRazorpayGatewayClient.setSimulateFailure(true);

            // Call dispatch with force=true -> expect HTTP 502 Bad Gateway
            mockMvc.perform(post("/api/recovery/cases/" + initialCase.getId() + "/dispatch?force=true"))
                    .andExpect(status().isBadGateway())
                    .andExpect(jsonPath("$.status").value("ACTION_FAILED"))
                    .andExpect(jsonPath("$.error").value("Payment gateway dispatch failed"))
                    .andExpect(jsonPath("$.recoveryCaseId").value(initialCase.getId().toString()))
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("timeout")));

            // Assert gateway was invoked exactly once during failed dispatch
            assertEquals(1, mockRazorpayGatewayClient.getInvocationCount(),
                    "Gateway must be invoked exactly ONCE during failed dispatch");

            // Reload directly from PostgreSQL to verify physical database persistence
            RecoveryCase persisted = recoveryCaseRepository.findById(initialCase.getId()).orElseThrow();
            assertEquals(RecoveryStatus.ACTION_FAILED.name(), persisted.getStatus(),
                    "RecoveryCase status must be physically committed as ACTION_FAILED in database");
            assertNotNull(persisted.getUpdatedAt());
            assertTrue(!persisted.getUpdatedAt().isBefore(preDispatchUpdatedAt),
                    "updatedAt must be updated and persisted upon failure");

            // Verify repeated dispatch is rejected because status is ACTION_FAILED (not ACTION_PENDING)
            mockMvc.perform(post("/api/recovery/cases/" + initialCase.getId() + "/dispatch?force=true"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("ACTION_FAILED")));

            // Assert gateway was NOT invoked again during rejected repeated dispatch
            assertEquals(1, mockRazorpayGatewayClient.getInvocationCount(),
                    "Gateway must NOT be invoked again on repeated dispatch attempt");

        } finally {
            mockRazorpayGatewayClient.setSimulateFailure(false);
            mockRazorpayGatewayClient.resetInvocationCount();
        }
    }
}
