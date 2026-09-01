package com.razorrecall.controller;

import com.razorrecall.service.WebhookProcessorService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/webhooks")
public class WebhookController {

    private final WebhookProcessorService webhookProcessorService;

    public WebhookController(WebhookProcessorService webhookProcessorService) {
        this.webhookProcessorService = webhookProcessorService;
    }

    @PostMapping("/razorpay")
    public ResponseEntity<?> receiveWebhook(@RequestBody String payload) {
        try {
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