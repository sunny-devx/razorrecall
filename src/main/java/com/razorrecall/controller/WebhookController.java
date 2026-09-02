package com.razorrecall.controller;

import com.razorrecall.dto.RazorpayWebhookPayload;
import com.razorrecall.service.MerchantWebhookSecretProvider;
import com.razorrecall.service.WebhookProcessorService;
import com.razorrecall.service.WebhookSignatureVerifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/webhooks")
public class WebhookController {

    private final WebhookProcessorService webhookProcessorService;
    private final WebhookSignatureVerifier webhookSignatureVerifier;
    private final MerchantWebhookSecretProvider merchantWebhookSecretProvider;

    public WebhookController(
            WebhookProcessorService webhookProcessorService,
            WebhookSignatureVerifier webhookSignatureVerifier,
            MerchantWebhookSecretProvider merchantWebhookSecretProvider
    ) {
        this.webhookProcessorService = webhookProcessorService;
        this.webhookSignatureVerifier = webhookSignatureVerifier;
        this.merchantWebhookSecretProvider = merchantWebhookSecretProvider;
    }

    @PostMapping("/razorpay")
    public ResponseEntity<?> receiveWebhook(
            @RequestHeader(value = "X-Razorpay-Signature", required = false) String signatureHeader,
            @RequestBody(required = false) String payload
    ) {
        if (payload == null || payload.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Payload cannot be empty"));
        }

        if (signatureHeader == null || signatureHeader.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Missing webhook signature"));
        }

        try {
            RazorpayWebhookPayload parsed = RazorpayWebhookPayload.parse(payload);
            String secret = merchantWebhookSecretProvider.getSecretForMerchant(parsed.getMerchantId());

            boolean isValid = webhookSignatureVerifier.verify(payload, signatureHeader, secret);
            if (!isValid) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Invalid webhook signature"));
            }

            WebhookProcessorService.WebhookProcessingResult result = webhookProcessorService.processWebhook(payload);
            return ResponseEntity.ok(result);

        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage() != null ? e.getMessage() : "Invalid webhook payload"));
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Invalid webhook payload"));
        }
    }
}