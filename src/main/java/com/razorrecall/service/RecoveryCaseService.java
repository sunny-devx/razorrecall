package com.razorrecall.service;

import com.razorrecall.domain.PaymentAttempt;
import com.razorrecall.domain.RecoveryCase;
import com.razorrecall.domain.RecoveryStatus;
import com.razorrecall.domain.RecoveryStrategy;
import com.razorrecall.dto.ActionExecutionResult;
import com.razorrecall.dto.AiDiagnosisResult;
import com.razorrecall.dto.RecoveryMetricsResponse;
import com.razorrecall.repository.RecoveryCaseRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
    private final RecoveryActionDispatcher recoveryActionDispatcher;
    private final PaymentAttemptService paymentAttemptService;

    public record EvaluationResult(
            RecoveryCase recoveryCase,
            RecoveryStrategy proposedStrategy,
            RecoveryStatus newStatus,
            OffsetDateTime nextActionAt,
            String message,
            AiDiagnosisResult aiDiagnosis) {
        public EvaluationResult(
                RecoveryCase recoveryCase,
                RecoveryStrategy proposedStrategy,
                RecoveryStatus newStatus,
                OffsetDateTime nextActionAt,
                String message) {
            this(recoveryCase, proposedStrategy, newStatus, nextActionAt, message, null);
        }
    }

    public record ReconciliationResult(
            RecoveryCase recoveryCase,
            boolean reconciled,
            String message) {
    }

    public RecoveryCaseService(
            RecoveryCaseRepository recoveryCaseRepository,
            FailureClassifier failureClassifier,
            RecoveryDecisionEngine recoveryDecisionEngine,
            RecoveryGuardrailService recoveryGuardrailService,
            RecoveryActionDispatcher recoveryActionDispatcher,
            PaymentAttemptService paymentAttemptService) {
        this.recoveryCaseRepository = recoveryCaseRepository;
        this.failureClassifier = failureClassifier;
        this.recoveryDecisionEngine = recoveryDecisionEngine;
        this.recoveryGuardrailService = recoveryGuardrailService;
        this.recoveryActionDispatcher = recoveryActionDispatcher;
        this.paymentAttemptService = paymentAttemptService;
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
        RecoveryGuardrailService.GuardrailResult guardrailResult = recoveryGuardrailService.validate(recoveryCase,
                decision);

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
                        guardrailResult.failureReason(),
                        decision.aiDiagnosis());
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
                decision.reason(),
                decision.aiDiagnosis());
    }

    @Transactional
    public ActionExecutionResult dispatchAction(UUID recoveryCaseId, boolean force) {
        if (recoveryCaseId == null) {
            throw new IllegalArgumentException("RecoveryCase ID must not be null");
        }

        RecoveryCase recoveryCase = recoveryCaseRepository.findById(recoveryCaseId)
                .orElseThrow(() -> new IllegalArgumentException("Recovery case not found for ID: " + recoveryCaseId));

        String currentStatus = recoveryCase.getStatus();
        if (!RecoveryStatus.ACTION_PENDING.name().equalsIgnoreCase(currentStatus)) {
            throw new IllegalStateException(
                    "Cannot dispatch case in status '" + currentStatus + "'. Case must be in ACTION_PENDING.");
        }

        OffsetDateTime now = OffsetDateTime.now();
        if (!force && recoveryCase.getNextActionAt() != null && recoveryCase.getNextActionAt().isAfter(now)) {
            throw new IllegalStateException("Timing guardrail: Recovery action is scheduled for "
                    + recoveryCase.getNextActionAt() + ". Use force=true to dispatch immediately.");
        }

        RecoveryDecisionEngine.RecoveryDecision decision = recoveryDecisionEngine.decide(recoveryCase);
        if (decision.strategy() == RecoveryStrategy.ABSTAIN
                || decision.strategy() == RecoveryStrategy.MANUAL_ESCALATE) {
            throw new IllegalStateException("Cannot dispatch non-actionable strategy: " + decision.strategy());
        }

        try {
            ActionExecutionResult result = recoveryActionDispatcher.dispatch(recoveryCase, decision.strategy());
            recoveryCase.setStatus(RecoveryStatus.WAITING_FOR_OUTCOME.name());
            recoveryCase.setUpdatedAt(now);
            recoveryCaseRepository.save(recoveryCase);
            return result;
        } catch (Exception e) {
            recoveryCase.setStatus(RecoveryStatus.ACTION_FAILED.name());
            recoveryCase.setUpdatedAt(now);
            recoveryCaseRepository.save(recoveryCase);
            return new ActionExecutionResult(
                    recoveryCase.getId(),
                    decision.strategy(),
                    RecoveryStatus.ACTION_FAILED,
                    null,
                    null,
                    now,
                    "Payment gateway dispatch failed: " + (e.getMessage() != null ? e.getMessage() : "Unknown error"));
        }
    }

    @Transactional
    public List<ActionExecutionResult> dispatchDueCases() {
        OffsetDateTime now = OffsetDateTime.now();
        List<RecoveryCase> pendingCases = recoveryCaseRepository.findAll().stream()
                .filter(rc -> RecoveryStatus.ACTION_PENDING.name().equalsIgnoreCase(rc.getStatus()))
                .filter(rc -> rc.getNextActionAt() == null || !rc.getNextActionAt().isAfter(now))
                .toList();

        return pendingCases.stream()
                .map(rc -> {
                    try {
                        return dispatchAction(rc.getId(), false);
                    } catch (Exception e) {
                        return new ActionExecutionResult(
                                rc.getId(),
                                null,
                                RecoveryStatus.ACTION_FAILED,
                                null,
                                null,
                                now,
                                "Batch dispatch failed: " + e.getMessage());
                    }
                })
                .toList();
    }

    @Transactional
    public List<EvaluationResult> evaluateDetectedCases() {
        List<RecoveryCase> detectedCases = recoveryCaseRepository.findAll().stream()
                .filter(rc -> RecoveryStatus.DETECTED.name().equalsIgnoreCase(rc.getStatus()))
                .toList();

        return detectedCases.stream()
                .map(rc -> evaluateCase(rc.getId()))
                .toList();
    }

    @Transactional
    public ReconciliationResult reconcileCapturedPayment(
            String orderId,
            String referenceId,
            String paymentId,
            BigDecimal amount) {
        RecoveryCase recoveryCase = null;

        // Priority 1: Primary - order_id matching failed PaymentAttempt.order_id
        if (orderId != null && !orderId.isBlank()) {
            Optional<PaymentAttempt> attemptOpt = paymentAttemptService.findLatestByOrderId(orderId);
            if (attemptOpt.isPresent()) {
                Optional<RecoveryCase> caseOpt = recoveryCaseRepository
                        .findByPaymentAttemptId(attemptOpt.get().getId());
                if (caseOpt.isPresent()) {
                    recoveryCase = caseOpt.get();
                }
            }
        }

        // Priority 2: Secondary - recovery_case_id carried through Payment Link
        // reference_id / notes
        if (recoveryCase == null && referenceId != null && !referenceId.isBlank()) {
            try {
                UUID caseId = UUID.fromString(referenceId.trim());
                recoveryCase = recoveryCaseRepository.findById(caseId).orElse(null);
            } catch (IllegalArgumentException ignored) {
                // referenceId is not a valid UUID, proceed to fallback
            }
        }

        // Priority 3: Fallback - razorpay_payment_id
        if (recoveryCase == null && paymentId != null && !paymentId.isBlank()) {
            Optional<PaymentAttempt> attemptOpt = paymentAttemptService.findByRazorpayPaymentId(paymentId);
            if (attemptOpt.isPresent()) {
                recoveryCase = recoveryCaseRepository.findByPaymentAttemptId(attemptOpt.get().getId()).orElse(null);
            }
        }

        // Handle unmatched captured payment gracefully
        if (recoveryCase == null) {
            return new ReconciliationResult(null, false, "No matching recovery case found for captured payment");
        }

        String currentStatus = recoveryCase.getStatus();

        // Idempotency: duplicate captured webhook must not perform transition twice
        if (RecoveryStatus.RECOVERED.name().equalsIgnoreCase(currentStatus)) {
            return new ReconciliationResult(recoveryCase, true, "Recovery case is already RECOVERED");
        }

        // Guardrail: only WAITING_FOR_OUTCOME or ACTION_PENDING may transition to
        // RECOVERED
        if (!RecoveryStatus.WAITING_FOR_OUTCOME.name().equalsIgnoreCase(currentStatus)
                && !RecoveryStatus.ACTION_PENDING.name().equalsIgnoreCase(currentStatus)) {
            return new ReconciliationResult(
                    recoveryCase,
                    false,
                    "Recovery case in status '" + currentStatus + "' cannot transition to RECOVERED");
        }

        OffsetDateTime now = OffsetDateTime.now();
        recoveryCase.setStatus(RecoveryStatus.RECOVERED.name());
        recoveryCase.setUpdatedAt(now);
        RecoveryCase savedCase = recoveryCaseRepository.save(recoveryCase);

        PaymentAttempt attempt = savedCase.getPaymentAttempt();
        if (attempt != null) {
            paymentAttemptService.markPaymentCaptured(attempt, amount, paymentId);
        }

        return new ReconciliationResult(savedCase, true, "Recovery case successfully transitioned to RECOVERED");
    }

    public RecoveryMetricsResponse getMetrics() {
        List<RecoveryCase> allCases = recoveryCaseRepository.findAll();

        long totalCases = allCases.size();
        long recoveredCases = 0;
        long actionPendingCases = 0;
        long waitingForOutcomeCases = 0;
        long abstainedCases = 0;
        long escalatedCases = 0;
        long failedCases = 0;
        long actionableCases = 0;

        BigDecimal totalAtRiskAmount = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        BigDecimal totalRecoveredAmount = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

        for (RecoveryCase rc : allCases) {
            String status = rc.getStatus();
            PaymentAttempt attempt = rc.getPaymentAttempt();
            BigDecimal attemptAmount = attempt != null && attempt.getAmount() != null
                    ? attempt.getAmount().setScale(2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

            totalAtRiskAmount = totalAtRiskAmount.add(attemptAmount);

            if (RecoveryStatus.RECOVERED.name().equalsIgnoreCase(status)) {
                recoveredCases++;
                totalRecoveredAmount = totalRecoveredAmount.add(attemptAmount);
                actionableCases++;
            } else if (RecoveryStatus.ACTION_PENDING.name().equalsIgnoreCase(status)) {
                actionPendingCases++;
                actionableCases++;
            } else if (RecoveryStatus.WAITING_FOR_OUTCOME.name().equalsIgnoreCase(status)) {
                waitingForOutcomeCases++;
                actionableCases++;
            } else if (RecoveryStatus.ABSTAINED.name().equalsIgnoreCase(status)) {
                abstainedCases++;
            } else if (RecoveryStatus.ESCALATED.name().equalsIgnoreCase(status)) {
                escalatedCases++;
            } else if (RecoveryStatus.ACTION_FAILED.name().equalsIgnoreCase(status)) {
                failedCases++;
                actionableCases++;
            } else if (rc.isEligible()) {
                actionableCases++;
            }
        }

        BigDecimal recoveryRatePercentage = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        if (actionableCases > 0) {
            recoveryRatePercentage = BigDecimal.valueOf(recoveredCases)
                    .multiply(BigDecimal.valueOf(100))
                    .divide(BigDecimal.valueOf(actionableCases), 2, RoundingMode.HALF_UP);
        }

        return new RecoveryMetricsResponse(
                totalCases,
                recoveredCases,
                actionPendingCases,
                waitingForOutcomeCases,
                abstainedCases,
                escalatedCases,
                failedCases,
                totalAtRiskAmount,
                totalRecoveredAmount,
                recoveryRatePercentage);
    }
}