package com.razorrecall.controller;

import com.razorrecall.domain.PaymentAttempt;
import com.razorrecall.domain.RecoveryCase;
import com.razorrecall.domain.RecoveryStatus;
import com.razorrecall.repository.PaymentAttemptRepository;
import com.razorrecall.repository.RecoveryCaseRepository;
import com.razorrecall.service.MerchantWebhookSecretProvider;
import com.razorrecall.service.WebhookSignatureVerifier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

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
    }

    @Test
    void testListAndFilterEndpointWorks() throws Exception {
        String paymentId = "pay_ctrl_list_" + System.currentTimeMillis();
        RecoveryCase created = ingestWebhook(paymentId, "GATEWAY_ERROR", "timeout", "Gateway timeout");

        mockMvc.perform(get("/api/recovery/cases?status=DETECTED&eligible=true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }
}
