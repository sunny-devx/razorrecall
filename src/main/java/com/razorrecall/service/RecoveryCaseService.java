package com.razorrecall.service;

import com.razorrecall.domain.PaymentAttempt;
import com.razorrecall.domain.RecoveryCase;
import com.razorrecall.domain.RecoveryStatus;
import com.razorrecall.domain.RecoveryStrategy;
import com.razorrecall.repository.RecoveryCaseRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class RecoveryCaseService {

    private final RecoveryCaseRepository recoveryCaseRepository;
    private final FailureClassifier failureClassifier;
    private final RecoveryDecisionEngine recoveryDecisionEngine;
    private final RecoveryGuardrailService recoveryGuardrailService;

    public record EvaluationResult(
            RecoveryCase recoveryCase,
            RecoveryStrategy proposedStrategy,
            RecoveryStatus newStatus,
            OffsetDateTime nextActionAt,
            String message
    ) {}

    public RecoveryCaseService(
            RecoveryCaseRepository recoveryCaseRepository,
            FailureClassifier failureClassifier,
            RecoveryDecisionEngine recoveryDecisionEngine,
            RecoveryGuardrailService recoveryGuardrailService) {
        this.recoveryCaseRepository = recoveryCaseRepository;
        this.failureClassifier = failureClassifier;
        this.recoveryDecisionEngine = recoveryDecisionEngine;
        this.recoveryGuardrailService = recoveryGuardrailService;
    }

    public RecoveryCase save(RecoveryCase recoveryCase) {
        return recoveryCaseRepository.save(recoveryCase);
    }

    public RecoveryCase findById(UUID id) {
        return recoveryCaseRepository.findById(id)
                .orElse(null);
    }

    public Optional<RecoveryCase> findByPaymentAttemptId(UUID paymentAttemptId) {
        if (paymentAttemptId == null) {
            return Optional.empty();
        }
        return recoveryCaseRepository.findByPaymentAttemptId(paymentAttemptId);
    }

    public List<RecoveryCase> listCases(String status, Boolean eligible) {
        List<RecoveryCase> all = recoveryCaseRepository.findAll();
        return all.stream()
                .filter(rc -> status == null || rc.getStatus().equalsIgnoreCase(status.trim()))
                .filter(rc -> eligible == null || rc.isEligible() == eligible)
                .toList();
    }

    @Transactional
    public RecoveryCase createOrGetRecoveryCase(
            PaymentAttempt paymentAttempt,
            String errorCode,
            String failureReason) {
        if (paymentAttempt == null || paymentAttempt.getId() == null) {
            throw new IllegalArgumentException("PaymentAttempt and PaymentAttempt ID must not be null");
        }

        Optional<RecoveryCase> existingCase = recoveryCaseRepository.findByPaymentAttemptId(paymentAttempt.getId());
        if (existingCase.isPresent()) {
            return existingCase.get();
        }

        FailureClassifier.ClassificationResult classification = failureClassifier.classify(errorCode, failureReason);

        OffsetDateTime now = OffsetDateTime.now();
        RecoveryCase recoveryCase = new RecoveryCase();
        recoveryCase.setId(UUID.randomUUID());
        recoveryCase.setPaymentAttempt(paymentAttempt);
        recoveryCase.setStatus(RecoveryStatus.DETECTED.name());
        recoveryCase.setFailureClass(classification.failureClass().name());
        recoveryCase.setEligible(classification.eligible());
        recoveryCase.setNextActionAt(classification.eligible() ? now.plusMinutes(5) : null);
        recoveryCase.setCreatedAt(now);
        recoveryCase.setUpdatedAt(now);

        return recoveryCaseRepository.save(recoveryCase);
    }

    @Transactional
    public EvaluationResult evaluateCase(UUID recoveryCaseId) {
        if (recoveryCaseId == null) {
            throw new IllegalArgumentException("RecoveryCase ID must not be null");
        }

        RecoveryCase recoveryCase = recoveryCaseRepository.findById(recoveryCaseId)
                .orElseThrow(() -> new IllegalArgumentException("Recovery case not found for ID: " + recoveryCaseId));

        RecoveryDecisionEngine.RecoveryDecision decision = recoveryDecisionEngine.decide(recoveryCase);
        RecoveryGuardrailService.GuardrailResult guardrailResult = recoveryGuardrailService.validate(recoveryCase, decision);

        if (!guardrailResult.passed()) {
            if (guardrailResult.targetStatus() == RecoveryStatus.STOPPED) {
                recoveryCase.setStatus(RecoveryStatus.STOPPED.name());
                recoveryCase.setNextActionAt(null);
                recoveryCase.setUpdatedAt(OffsetDateTime.now());
                RecoveryCase saved = recoveryCaseRepository.save(recoveryCase);
                return new EvaluationResult(
                        saved,
                        decision.strategy(),
                        RecoveryStatus.STOPPED,
                        null,
                        guardrailResult.failureReason()
                );
            }
            throw new IllegalStateException(guardrailResult.failureReason());
        }

        recoveryCase.setStatus(guardrailResult.targetStatus().name());
        recoveryCase.setNextActionAt(guardrailResult.nextActionAt());
        recoveryCase.setUpdatedAt(OffsetDateTime.now());

        RecoveryCase updated = recoveryCaseRepository.save(recoveryCase);

        return new EvaluationResult(
                updated,
                decision.strategy(),
                guardrailResult.targetStatus(),
                guardrailResult.nextActionAt(),
                decision.reason()
        );
    }
}