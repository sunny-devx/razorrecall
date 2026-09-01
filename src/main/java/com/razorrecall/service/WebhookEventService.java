package com.razorrecall.service;

import com.razorrecall.domain.WebhookEvent;
import com.razorrecall.repository.WebhookEventRepository;
import org.springframework.stereotype.Service;

@Service
public class WebhookEventService {

    private final WebhookEventRepository webhookEventRepository;

    public WebhookEventService(WebhookEventRepository webhookEventRepository) {
        this.webhookEventRepository = webhookEventRepository;
    }

    public WebhookEvent save(WebhookEvent webhookEvent) {
        if (webhookEvent.getEventKey() != null
                && webhookEventRepository.findByEventKey(webhookEvent.getEventKey()).isPresent()) {
            return webhookEventRepository
                    .findByEventKey(webhookEvent.getEventKey())
                    .orElseThrow();
        }

        return webhookEventRepository.save(webhookEvent);
    }
}