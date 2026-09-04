package com.razorrecall.controller;

import com.razorrecall.domain.PaymentAttempt;
import com.razorrecall.domain.RecoveryCase;
import com.razorrecall.domain.RecoveryStatus;
import com.razorrecall.repository.PaymentAttemptRepository;
import com.razorrecall.repository.RecoveryCaseRepository;
import com.razorrecall.service.MerchantWebhookSecretProvider;
import com.razorrecall.service.WebhookSignatureVerifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RecoverySchedulerControllerTest {

    @Nested
    @SpringBootTest(properties = {
            "razorrecall.webhook.secret=test_webhook_secret_key_12345"
    })
    @AutoConfigureMockMvc
    @DisplayName("On-Demand Scheduler Controller Tests (Scheduler Disabled by Default)")
    class OnDemandExecutionTests {

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

        private RecoveryCase ingestFailedWebhook(String paymentId) throws Exception {
            String jsonPayload = """
                {
                  "event": "payment.failed",
                  "payload": {
                    "payment": {
                      "entity": {
                        "id": "%s",
                        "amount": 150000,
                        "currency": "INR",
                        "status": "failed",
                        "error_code": "GATEWAY_ERROR",
                        "error_reason": "gateway_timeout",
                        "error_description": "Temporary gateway timeout"
                      }
                    }
                  }
                }
                """.formatted(paymentId);

            String secret = secretProvider.getDefaultSecret();
            String signature = signatureVerifier.calculateHmacSha256(jsonPayload, secret);

            mockMvc.perform(post("/api/webhooks/razorpay")
                            .header("X-Razorpay-Signature", signature)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(jsonPayload))
                    .andExpect(status().isOk());

            PaymentAttempt attempt = paymentAttemptRepository.findByRazorpayPaymentId(paymentId).orElseThrow();
            return recoveryCaseRepository.findByPaymentAttemptId(attempt.getId()).orElseThrow();
        }

        @Test
        @DisplayName("GET /api/recovery/scheduler/status returns standby status when disabled")
        void testGetSchedulerStatus() throws Exception {
            mockMvc.perform(get("/api/recovery/scheduler/status"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.enabled").value(false))
                    .andExpect(jsonPath("$.status").value(containsString("STANDBY")))
                    .andExpect(jsonPath("$.expiryWindowHours").value(24));
        }

        @Test
        @DisplayName("POST /api/recovery/scheduler/run executes autonomous cycle on demand")
        void testRunAutonomousCycleOnDemand() throws Exception {
            String payId = "pay_sched_ctrl_" + System.currentTimeMillis();
            RecoveryCase rc = ingestFailedWebhook(payId);

            mockMvc.perform(post("/api/recovery/scheduler/run"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("COMPLETED"))
                    .andExpect(jsonPath("$.evaluated", greaterThanOrEqualTo(1)))
                    .andExpect(jsonPath("$.dispatched", greaterThanOrEqualTo(0)))
                    .andExpect(jsonPath("$.expired", greaterThanOrEqualTo(0)))
                    .andExpect(jsonPath("$.message").value(containsString("Autonomous recovery cycle")));

            // Verify the case transitioned from DETECTED to ACTION_PENDING or beyond
            RecoveryCase evaluatedCase = recoveryCaseRepository.findById(rc.getId()).orElseThrow();
            assertTrue(
                    RecoveryStatus.ACTION_PENDING.name().equals(evaluatedCase.getStatus())
                            || RecoveryStatus.WAITING_FOR_OUTCOME.name().equals(evaluatedCase.getStatus())
            );
        }
    }

    @Nested
    @SpringBootTest(properties = {
            "razorrecall.scheduler.enabled=true",
            "razorrecall.webhook.secret=test_webhook_secret_key_12345"
    })
    @AutoConfigureMockMvc
    @DisplayName("Active Scheduler Controller Tests (Scheduler Enabled)")
    class ActiveSchedulerExecutionTests {

        @Autowired
        private MockMvc mockMvc;

        @Test
        @DisplayName("GET /api/recovery/scheduler/status returns active status when enabled")
        void testGetSchedulerStatusActive() throws Exception {
            mockMvc.perform(get("/api/recovery/scheduler/status"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.enabled").value(true))
                    .andExpect(jsonPath("$.status").value(containsString("ACTIVE")));
        }

        @Test
        @DisplayName("POST /api/recovery/scheduler/run triggers active scheduler service cycle")
        void testRunAutonomousCycleActive() throws Exception {
            mockMvc.perform(post("/api/recovery/scheduler/run"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("COMPLETED"))
                    .andExpect(jsonPath("$.message").value(containsString("active scheduler service")));
        }
    }
}
