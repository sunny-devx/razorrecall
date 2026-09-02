package com.razorrecall.controller;

import com.razorrecall.domain.PaymentAttempt;
import com.razorrecall.domain.RecoveryCase;
import com.razorrecall.domain.RecoveryStatus;
import com.razorrecall.domain.WebhookEvent;
import com.razorrecall.repository.PaymentAttemptRepository;
import com.razorrecall.repository.RecoveryCaseRepository;
import com.razorrecall.repository.WebhookEventRepository;
import com.razorrecall.service.MerchantWebhookSecretProvider;
import com.razorrecall.service.WebhookSignatureVerifier;
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

@SpringBootTest(properties = "razorrecall.webhook.secret=test_webhook_secret_key_12345")
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

  @Autowired
  private WebhookSignatureVerifier signatureVerifier;

  @Autowired
  private MerchantWebhookSecretProvider secretProvider;

  @Test
  void testReceivePaymentFailed_ValidSignature_CreatesRecoveryCase() throws Exception {
    String paymentId = "pay_valid_" + System.currentTimeMillis();
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

    String secret = secretProvider.getSecretForMerchant("acc_buildathon_1");
    String validSignature = signatureVerifier.calculateHmacSha256(jsonPayload, secret);

    mockMvc.perform(post("/api/webhooks/razorpay")
        .header("X-Razorpay-Signature", validSignature)
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
  void testReceivePaymentFailed_InvalidSignature_Rejected() throws Exception {
    String paymentId = "pay_invalid_sig_" + System.currentTimeMillis();
    String jsonPayload = """
        {
          "event": "payment.failed",
          "payload": {
            "payment": {
              "entity": {
                "id": "%s",
                "amount": 50000,
                "currency": "INR",
                "error_code": "GATEWAY_ERROR",
                "error_description": "Temporary network timeout"
              }
            }
          }
        }
        """.formatted(paymentId);

    String invalidSignature = "invalid_bogus_signature_hex_1234567890abcdef";

    mockMvc.perform(post("/api/webhooks/razorpay")
        .header("X-Razorpay-Signature", invalidSignature)
        .contentType(MediaType.APPLICATION_JSON)
        .content(jsonPayload))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("Invalid webhook signature"));

    // Verify NO database persistence occurs
    Optional<WebhookEvent> eventOpt = webhookEventRepository.findByEventKey("payment.failed:" + paymentId);
    assertFalse(eventOpt.isPresent(), "WebhookEvent must NOT be persisted on invalid signature");

    Optional<PaymentAttempt> attemptOpt = paymentAttemptRepository.findByRazorpayPaymentId(paymentId);
    assertFalse(attemptOpt.isPresent(), "PaymentAttempt must NOT be created on invalid signature");
  }

  @Test
  void testReceivePaymentFailed_MissingSignatureHeader_Rejected() throws Exception {
    String paymentId = "pay_missing_sig_" + System.currentTimeMillis();
    String jsonPayload = """
        {
          "event": "payment.failed",
          "payment_id": "%s",
          "amount": 100.00
        }
        """.formatted(paymentId);

    mockMvc.perform(post("/api/webhooks/razorpay")
        .contentType(MediaType.APPLICATION_JSON)
        .content(jsonPayload))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("Missing webhook signature"));

    Optional<PaymentAttempt> attemptOpt = paymentAttemptRepository.findByRazorpayPaymentId(paymentId);
    assertFalse(attemptOpt.isPresent(), "PaymentAttempt must NOT be created when signature is missing");
  }

  @Test
  void testReceivePaymentFailed_TamperedPayload_Rejected() throws Exception {
    String paymentId = "pay_tamper_" + System.currentTimeMillis();
    String originalPayload = """
        {
          "event": "payment.failed",
          "payment_id": "%s",
          "amount": 100.00
        }
        """.formatted(paymentId);

    String secret = secretProvider.getDefaultSecret();
    String signatureForOriginal = signatureVerifier.calculateHmacSha256(originalPayload, secret);

    String tamperedPayload = """
        {
          "event": "payment.failed",
          "payment_id": "%s",
          "amount": 999.00
        }
        """.formatted(paymentId);

    // Send tampered payload with original signature
    mockMvc.perform(post("/api/webhooks/razorpay")
        .header("X-Razorpay-Signature", signatureForOriginal)
        .contentType(MediaType.APPLICATION_JSON)
        .content(tamperedPayload))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("Invalid webhook signature"));

    Optional<PaymentAttempt> attemptOpt = paymentAttemptRepository.findByRazorpayPaymentId(paymentId);
    assertFalse(attemptOpt.isPresent(), "Tampered payload must be rejected");
  }

  @Test
  void testReceivePaymentFailed_DuplicateValidWebhook_Idempotent() throws Exception {
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

    String secret = secretProvider.getDefaultSecret();
    String signature = signatureVerifier.calculateHmacSha256(jsonPayload, secret);

    // First valid ingestion
    mockMvc.perform(post("/api/webhooks/razorpay")
        .header("X-Razorpay-Signature", signature)
        .contentType(MediaType.APPLICATION_JSON)
        .content(jsonPayload))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("PROCESSED"));

    // Second identical valid ingestion
    mockMvc.perform(post("/api/webhooks/razorpay")
        .header("X-Razorpay-Signature", signature)
        .contentType(MediaType.APPLICATION_JSON)
        .content(jsonPayload))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("DUPLICATE"));
  }

  @Test
  void testReceivePaymentFailed_WrongMerchantSecret_Rejected() throws Exception {
    String customMerchantId = "merchant_custom_abc";
    secretProvider.registerMerchantSecret(customMerchantId, "custom_secret_key_99999");

    String paymentId = "pay_merchant_mismatch_" + System.currentTimeMillis();
    String jsonPayload = """
        {
          "account_id": "%s",
          "event": "payment.failed",
          "payment_id": "%s",
          "amount": 500.00
        }
        """.formatted(customMerchantId, paymentId);

    // Sign using the default secret instead of the merchant's registered secret
    String wrongSecret = secretProvider.getDefaultSecret();
    String signatureWithWrongSecret = signatureVerifier.calculateHmacSha256(jsonPayload, wrongSecret);

    mockMvc.perform(post("/api/webhooks/razorpay")
        .header("X-Razorpay-Signature", signatureWithWrongSecret)
        .contentType(MediaType.APPLICATION_JSON)
        .content(jsonPayload))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("Invalid webhook signature"));

    Optional<PaymentAttempt> attemptOpt = paymentAttemptRepository.findByRazorpayPaymentId(paymentId);
    assertFalse(attemptOpt.isPresent(), "Mismatch merchant secret must be rejected");
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

    String secret = secretProvider.getDefaultSecret();
    String validSignature = signatureVerifier.calculateHmacSha256(jsonPayload, secret);

    mockMvc.perform(post("/api/webhooks/razorpay")
        .header("X-Razorpay-Signature", validSignature)
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
  void testInvalidWebhook_BadRequest() throws Exception {
    String secret = secretProvider.getDefaultSecret();

    // Missing event (signed)
    String missingEventPayload = "{\"payment_id\": \"pay_123\"}";
    String sig1 = signatureVerifier.calculateHmacSha256(missingEventPayload, secret);
    mockMvc.perform(post("/api/webhooks/razorpay")
        .header("X-Razorpay-Signature", sig1)
        .contentType(MediaType.APPLICATION_JSON)
        .content(missingEventPayload))
        .andExpect(status().isBadRequest());

    // Missing payment_id (signed)
    String missingPaymentIdPayload = "{\"event\": \"payment.failed\"}";
    String sig2 = signatureVerifier.calculateHmacSha256(missingPaymentIdPayload, secret);
    mockMvc.perform(post("/api/webhooks/razorpay")
        .header("X-Razorpay-Signature", sig2)
        .contentType(MediaType.APPLICATION_JSON)
        .content(missingPaymentIdPayload))
        .andExpect(status().isBadRequest());

    // Empty body
    mockMvc.perform(post("/api/webhooks/razorpay")
        .header("X-Razorpay-Signature", "some_sig")
        .contentType(MediaType.APPLICATION_JSON)
        .content(""))
        .andExpect(status().isBadRequest());
  }
}
