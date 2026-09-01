package com.razorrecall.service;

import com.razorrecall.domain.PaymentAttempt;
import com.razorrecall.domain.RecoveryCase;
import com.razorrecall.domain.RecoveryStatus;
import com.razorrecall.repository.RecoveryCaseRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

@Service
public class RecoveryCaseService {

    private final RecoveryCaseRepository recoveryCaseRepository;
    private final FailureClassifier failureClassifier;

    public RecoveryCaseService(
            RecoveryCaseRepository recoveryCaseRepository,
            FailureClassifier failureClassifier
    ) {
        this.recoveryCaseRepository = recoveryCaseRepository;
        this.failureClassifier = failureClassifier;
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

    @Transactional
    public RecoveryCase createOrGetRecoveryCase(
            PaymentAttempt paymentAttempt,
            String errorCode,
            String failureReason
    ) {
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
}