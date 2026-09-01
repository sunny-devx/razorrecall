package com.razorrecall.controller;

import com.razorrecall.domain.WebhookEvent;
import com.razorrecall.service.WebhookEventService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/webhooks")
public class WebhookController {

    private final WebhookEventService webhookEventService;

    public WebhookController(WebhookEventService webhookEventService) {
        this.webhookEventService = webhookEventService;
    }

    @PostMapping("/razorpay")
    public ResponseEntity<String> receiveWebhook(@RequestBody String payload) {

        try {
            String eventType = extractJsonValue(payload, "event");
            String paymentId = extractJsonValue(payload, "payment_id");

            if (eventType == null || eventType.isBlank()) {
                return ResponseEntity.badRequest()
                        .body("Missing webhook event type");
            }

            if (paymentId == null || paymentId.isBlank()) {
                return ResponseEntity.badRequest()
                        .body("Missing payment_id");
            }

            String eventKey = eventType + ":" + paymentId;

            WebhookEvent webhookEvent = new WebhookEvent();

            webhookEvent.setId(UUID.randomUUID());
            webhookEvent.setEventType(eventType);
            webhookEvent.setEventKey(eventKey);
            webhookEvent.setPayload(payload);

            webhookEventService.save(webhookEvent);

            return ResponseEntity.ok("Webhook received");

        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body("Invalid webhook payload");
        }
    }

    private String extractJsonValue(String payload, String key) {

        String regex = "\"" + Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]*)\"";

        Pattern pattern = Pattern.compile(regex);
        Matcher matcher = pattern.matcher(payload);

        if (matcher.find()) {
            return matcher.group(1);
        }

        return null;
    }
}