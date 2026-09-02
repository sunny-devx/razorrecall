package com.razorrecall.controller;

import com.razorrecall.domain.PaymentAttempt;
import com.razorrecall.domain.RecoveryCase;
import com.razorrecall.domain.RecoveryStatus;
import com.razorrecall.repository.PaymentAttemptRepository;
import com.razorrecall.repository.RecoveryCaseRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "razorrecall.webhook.secret=test_webhook_secret_key_12345")
@AutoConfigureMockMvc
class RecoveryMetricsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PaymentAttemptRepository paymentAttemptRepository;

    @Autowired
    private RecoveryCaseRepository recoveryCaseRepository;

    @Autowired
    private com.razorrecall.service.MerchantService merchantService;

    @Autowired
    private com.razorrecall.service.RecoveryCaseService recoveryCaseService;

    @Test
    void testGetRecoveryMetrics_ReturnsAccurateMetrics() throws Exception {
        // Ensure at least one recovered case exists with valid foreign key
        com.razorrecall.domain.Merchant merchant = merchantService.resolveMerchant("acc_metrics_test");
        UUID merchantId = merchant.getId();

        PaymentAttempt attempt = new PaymentAttempt();
        attempt.setId(UUID.randomUUID());
        attempt.setMerchantId(merchantId);
        attempt.setAmount(new BigDecimal("5000.00"));
        attempt.setCurrency("INR");
        attempt.setStatus("CAPTURED");
        attempt.setOrderId("order_metrics_test_" + System.currentTimeMillis());
        attempt.setCreatedAt(OffsetDateTime.now());
        paymentAttemptRepository.save(attempt);

        RecoveryCase rc = new RecoveryCase();
        rc.setId(UUID.randomUUID());
        rc.setPaymentAttempt(attempt);
        rc.setStatus(RecoveryStatus.RECOVERED.name());
        rc.setEligible(true);
        rc.setCreatedAt(OffsetDateTime.now());
        rc.setUpdatedAt(OffsetDateTime.now());
        recoveryCaseRepository.save(rc);

        // Test primary endpoint: GET /api/recovery/metrics
        mockMvc.perform(get("/api/recovery/metrics")
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCases", greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.recoveredCases", greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.actionPendingCases", greaterThanOrEqualTo(0)))
                .andExpect(jsonPath("$.waitingForOutcomeCases", greaterThanOrEqualTo(0)))
                .andExpect(jsonPath("$.abstainedCases", greaterThanOrEqualTo(0)))
                .andExpect(jsonPath("$.escalatedCases", greaterThanOrEqualTo(0)))
                .andExpect(jsonPath("$.failedCases", greaterThanOrEqualTo(0)))
                .andExpect(jsonPath("$.totalAtRiskAmount", notNullValue()))
                .andExpect(jsonPath("$.totalRecoveredAmount", notNullValue()))
                .andExpect(jsonPath("$.recoveryRatePercentage", notNullValue()));

        // Test alias endpoint: GET /api/recovery/cases/metrics
        mockMvc.perform(get("/api/recovery/cases/metrics")
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCases", greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.recoveredCases", greaterThanOrEqualTo(1)));
    }

    @Test
    void testGetRecoveryMetrics_ExactCalculationIntegrity() throws Exception {
        com.razorrecall.dto.RecoveryMetricsResponse metrics = recoveryCaseService.getMetrics();
        assertNotNull(metrics);

        mockMvc.perform(get("/api/recovery/metrics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCases").value(metrics.totalCases()))
                .andExpect(jsonPath("$.recoveredCases").value(metrics.recoveredCases()))
                .andExpect(jsonPath("$.actionPendingCases").value(metrics.actionPendingCases()))
                .andExpect(jsonPath("$.waitingForOutcomeCases").value(metrics.waitingForOutcomeCases()))
                .andExpect(jsonPath("$.abstainedCases").value(metrics.abstainedCases()))
                .andExpect(jsonPath("$.escalatedCases").value(metrics.escalatedCases()))
                .andExpect(jsonPath("$.failedCases").value(metrics.failedCases()))
                .andExpect(jsonPath("$.totalAtRiskAmount").value(metrics.totalAtRiskAmount().doubleValue()))
                .andExpect(jsonPath("$.totalRecoveredAmount").value(metrics.totalRecoveredAmount().doubleValue()))
                .andExpect(jsonPath("$.recoveryRatePercentage").value(metrics.recoveryRatePercentage().doubleValue()));
    }
}
