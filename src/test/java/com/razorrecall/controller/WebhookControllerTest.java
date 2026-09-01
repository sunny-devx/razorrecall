package com.razorrecall.controller;

import com.razorrecall.domain.PaymentAttempt;
import com.razorrecall.domain.RecoveryCase;
import com.razorrecall.domain.RecoveryStatus;
import com.razorrecall.domain.WebhookEvent;
import com.razorrecall.repository.PaymentAttemptRepository;
import com.razorrecall.repository.RecoveryCaseRepository;
import com.razorrecall.repository.WebhookEventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class WebhookControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private WebhookEventRepository webhookEventRepository;

  @Autowired
  private PaymentAttemptRepository paymentAttemptRepository;

  @Autowired
  private RecoveryCaseRepository recoveryCaseRepository;

  @Test
  void testReceivePaymentFailed_NestedPayload_CreatesRecoveryCase() throws Exception {
    String paymentId = "pay_test_" + System.currentTimeMillis();
    String jsonPayload = """
        {
          "entity": "event",
          "account_id": "acc_buildathon_1",
          "event": "payment.failed",
          "contains": ["payment"],
          "payload": {
            "payment": {
              "entity": {
                "id": "%s",
                "entity": "payment",
                "amount": 250000,
                "currency": "INR",
                "status": "failed",
                "order_id": "order_test_99",
                "method": "card",
                "error_code": "GATEWAY_ERROR",
                "error_description": "Payment was declined due to a temporary bank gateway error",
                "error_source": "gateway",
                "error_step": "payment_authorization",
                "error_reason": "gateway_timeout"
              }
            }
          },
          "created_at": 1700000000
        }
        """.formatted(paymentId);

    mockMvc.perform(post("/api/webhooks/razorpay")
        .contentType(MediaType.APPLICATION_JSON)
        .content(jsonPayload))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("PROCESSED"))
        .andExpect(jsonPath("$.eventType").value("payment.failed"))
        .andExpect(jsonPath("$.recoveryStatus").value("DETECTED"))
        .andExpect(jsonPath("$.failureClass").value("SOFT"))
        .andExpect(jsonPath("$.eligible").value(true));

    // Verify database persistence
    Optional<WebhookEvent> eventOpt = webhookEventRepository.findByEventKey("payment.failed:" + paymentId);
    assertTrue(eventOpt.isPresent(), "WebhookEvent should be persisted");

    Optional<PaymentAttempt> attemptOpt = paymentAttemptRepository.findByRazorpayPaymentId(paymentId);
    assertTrue(attemptOpt.isPresent(), "PaymentAttempt should be persisted");
    assertEquals("FAILED", attemptOpt.get().getStatus());
    assertEquals("INR", attemptOpt.get().getCurrency());

    Optional<RecoveryCase> caseOpt = recoveryCaseRepository.findByPaymentAttemptId(attemptOpt.get().getId());
    assertTrue(caseOpt.isPresent(), "RecoveryCase should be persisted");
    RecoveryCase recoveryCase = caseOpt.get();
    assertEquals(RecoveryStatus.DETECTED.name(), recoveryCase.getStatus());
    assertEquals("SOFT", recoveryCase.getFailureClass());
    assertTrue(recoveryCase.isEligible());
    assertNotNull(recoveryCase.getNextActionAt());
  }

  @Test
  void testReceivePaymentFailed_HardFailure_Ineligible() throws Exception {
    String paymentId = "pay_hard_" + System.currentTimeMillis();
    String jsonPayload = """
        {
          "event": "payment.failed",
          "payload": {
            "payment": {
              "entity": {
                "id": "%s",
                "amount": 10000,
                "currency": "INR",
                "error_code": "CARD_EXPIRED",
                "error_description": "Card has expired"
              }
            }
          }
        }
        """.formatted(paymentId);

    mockMvc.perform(post("/api/webhooks/razorpay")
        .contentType(MediaType.APPLICATION_JSON)
        .content(jsonPayload))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("PROCESSED"))
        .andExpect(jsonPath("$.failureClass").value("HARD"))
        .andExpect(jsonPath("$.eligible").value(false));

    Optional<PaymentAttempt> attemptOpt = paymentAttemptRepository.findByRazorpayPaymentId(paymentId);
    assertTrue(attemptOpt.isPresent());

    Optional<RecoveryCase> caseOpt = recoveryCaseRepository.findByPaymentAttemptId(attemptOpt.get().getId());
    assertTrue(caseOpt.isPresent());
    assertEquals("HARD", caseOpt.get().getFailureClass());
    assertFalse(caseOpt.get().isEligible());
  }

  @Test
  void testDuplicateWebhook_Idempotency() throws Exception {
    String paymentId = "pay_dup_" + System.currentTimeMillis();
    String jsonPayload = """
        {
          "event": "payment.failed",
          "payment_id": "%s",
          "amount": 150.00,
          "currency": "INR",
          "failure_reason": "temporary gateway issue",
          "error_code": "GATEWAY_ERROR"
        }
        """.formatted(paymentId);

    // First ingestion
    mockMvc.perform(post("/api/webhooks/razorpay")
        .contentType(MediaType.APPLICATION_JSON)
        .content(jsonPayload))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("PROCESSED"));

    // Second identical ingestion
    mockMvc.perform(post("/api/webhooks/razorpay")
        .contentType(MediaType.APPLICATION_JSON)
        .content(jsonPayload))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("DUPLICATE"));
  }

  @Test
  void testInvalidWebhook_BadRequest() throws Exception {
    // Missing event
    mockMvc.perform(post("/api/webhooks/razorpay")
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"payment_id\": \"pay_123\"}"))
        .andExpect(status().isBadRequest());

    // Missing payment_id
    mockMvc.perform(post("/api/webhooks/razorpay")
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"event\": \"payment.failed\"}"))
        .andExpect(status().isBadRequest());

    // Empty body
    mockMvc.perform(post("/api/webhooks/razorpay")
        .contentType(MediaType.APPLICATION_JSON)
        .content(""))
        .andExpect(status().isBadRequest());
  }
}
