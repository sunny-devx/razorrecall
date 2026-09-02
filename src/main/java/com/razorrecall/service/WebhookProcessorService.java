package com.razorrecall.service;

import com.razorrecall.domain.Merchant;
import com.razorrecall.domain.PaymentAttempt;
import com.razorrecall.domain.RecoveryCase;
import com.razorrecall.domain.WebhookEvent;
import com.razorrecall.dto.RazorpayWebhookPayload;
import com.razorrecall.repository.WebhookEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
public class WebhookProcessorService {

    private final WebhookEventService webhookEventService;
    private final WebhookEventRepository webhookEventRepository;
    private final MerchantService merchantService;
    private final PaymentAttemptService paymentAttemptService;
    private final RecoveryCaseService recoveryCaseService;

    public record WebhookProcessingResult(
            String status,
            String eventType,
            String eventKey,
            UUID webhookEventId,
            UUID paymentAttemptId,
            UUID recoveryCaseId,
            String recoveryStatus,
            String failureClass,
            Boolean eligible,
            String message
    ) {}

    public WebhookProcessorService(
            WebhookEventService webhookEventService,
            WebhookEventRepository webhookEventRepository,
            MerchantService merchantService,
            PaymentAttemptService paymentAttemptService,
            RecoveryCaseService recoveryCaseService
    ) {
        this.webhookEventService = webhookEventService;
        this.webhookEventRepository = webhookEventRepository;
        this.merchantService = merchantService;
        this.paymentAttemptService = paymentAttemptService;
        this.recoveryCaseService = recoveryCaseService;
    }

    @Transactional
    public WebhookProcessingResult processWebhook(String rawPayload) {
        RazorpayWebhookPayload payload = RazorpayWebhookPayload.parse(rawPayload);

        String eventKey = payload.getEventType() + ":" + payload.getPaymentId();

        Optional<WebhookEvent> existingEvent = webhookEventRepository.findByEventKey(eventKey);
        if (existingEvent.isPresent()) {
            WebhookEvent event = existingEvent.get();
            Optional<PaymentAttempt> existingAttempt = paymentAttemptService.findByRazorpayPaymentId(payload.getPaymentId());
            if (existingAttempt.isEmpty() && payload.getOrderId() != null) {
                existingAttempt = paymentAttemptService.findLatestByOrderId(payload.getOrderId());
            }
            Optional<RecoveryCase> existingCase = existingAttempt.flatMap(att -> recoveryCaseService.findByPaymentAttemptId(att.getId()));
            if (existingCase.isEmpty() && payload.getReferenceId() != null) {
                try {
                    UUID caseId = UUID.fromString(payload.getReferenceId().trim());
                    existingCase = Optional.ofNullable(recoveryCaseService.findById(caseId));
                } catch (IllegalArgumentException ignored) {}
            }

            return new WebhookProcessingResult(
                    "DUPLICATE",
                    payload.getEventType(),
                    eventKey,
                    event.getId(),
                    existingAttempt.map(PaymentAttempt::getId).orElse(null),
                    existingCase.map(RecoveryCase::getId).orElse(null),
                    existingCase.map(RecoveryCase::getStatus).orElse(null),
                    existingCase.map(RecoveryCase::getFailureClass).orElse(null),
                    existingCase.map(RecoveryCase::isEligible).orElse(null),
                    "Webhook event already processed (idempotent duplicate)"
            );
        }

        // Persist new WebhookEvent
        WebhookEvent webhookEvent = new WebhookEvent();
        webhookEvent.setId(UUID.randomUUID());
        webhookEvent.setEventType(payload.getEventType());
        webhookEvent.setEventKey(eventKey);
        webhookEvent.setPayload(rawPayload);
        webhookEvent = webhookEventService.save(webhookEvent);

        if ("payment.failed".equalsIgnoreCase(payload.getEventType())) {
            Merchant merchant = merchantService.resolveMerchant(payload.getMerchantId());

            PaymentAttempt paymentAttempt = paymentAttemptService.recordFailedPayment(
                    merchant.getId(),
                    payload.getPaymentId(),
                    payload.getOrderId(),
                    payload.getAmount(),
                    payload.getCurrency(),
                    payload.getErrorReason()
            );

            RecoveryCase recoveryCase = recoveryCaseService.createOrGetRecoveryCase(
                    paymentAttempt,
                    payload.getErrorCode(),
                    payload.getErrorReason()
            );

            return new WebhookProcessingResult(
                    "PROCESSED",
                    payload.getEventType(),
                    eventKey,
                    webhookEvent.getId(),
                    paymentAttempt.getId(),
                    recoveryCase.getId(),
                    recoveryCase.getStatus(),
                    recoveryCase.getFailureClass(),
                    recoveryCase.isEligible(),
                    "Payment failure recorded and recovery case initialized"
            );
        }

        if ("payment.captured".equalsIgnoreCase(payload.getEventType())) {
            RecoveryCaseService.ReconciliationResult reconciliation = recoveryCaseService.reconcileCapturedPayment(
                    payload.getOrderId(),
                    payload.getReferenceId(),
                    payload.getPaymentId(),
                    payload.getAmount()
            );

            if (reconciliation.reconciled() && reconciliation.recoveryCase() != null) {
                RecoveryCase rc = reconciliation.recoveryCase();
                PaymentAttempt attempt = rc.getPaymentAttempt();
                return new WebhookProcessingResult(
                        "RECOVERED",
                        payload.getEventType(),
                        eventKey,
                        webhookEvent.getId(),
                        attempt != null ? attempt.getId() : null,
                        rc.getId(),
                        rc.getStatus(),
                        rc.getFailureClass(),
                        rc.isEligible(),
                        reconciliation.message()
                );
            }

            return new WebhookProcessingResult(
                    "RECORDED",
                    payload.getEventType(),
                    eventKey,
                    webhookEvent.getId(),
                    null,
                    reconciliation.recoveryCase() != null ? reconciliation.recoveryCase().getId() : null,
                    reconciliation.recoveryCase() != null ? reconciliation.recoveryCase().getStatus() : null,
                    reconciliation.recoveryCase() != null ? reconciliation.recoveryCase().getFailureClass() : null,
                    reconciliation.recoveryCase() != null ? reconciliation.recoveryCase().isEligible() : null,
                    reconciliation.message()
            );
        }

        return new WebhookProcessingResult(
                "RECORDED",
                payload.getEventType(),
                eventKey,
                webhookEvent.getId(),
                null,
                null,
                null,
                null,
                null,
                "Webhook event recorded (non-failure event)"
        );
    }
}
