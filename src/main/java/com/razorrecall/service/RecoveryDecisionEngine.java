package com.razorrecall.service;

import com.razorrecall.domain.FailureClass;
import com.razorrecall.domain.PaymentAttempt;
import com.razorrecall.domain.RecoveryCase;
import com.razorrecall.domain.RecoveryStrategy;
import com.razorrecall.dto.AiDiagnosisResult;
import com.razorrecall.dto.FailureContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
public class RecoveryDecisionEngine {

    private static final Logger log = LoggerFactory.getLogger(RecoveryDecisionEngine.class);

    private final AiDiagnosisService aiDiagnosisService;

    public record RecoveryDecision(
            RecoveryStrategy strategy,
            long suggestedDelaySeconds,
            String reason,
            AiDiagnosisResult aiDiagnosis
    ) {
        public RecoveryDecision(RecoveryStrategy strategy, long suggestedDelaySeconds, String reason) {
            this(strategy, suggestedDelaySeconds, reason, null);
        }
    }

    @Autowired
    public RecoveryDecisionEngine(AiDiagnosisService aiDiagnosisService) {
        this.aiDiagnosisService = aiDiagnosisService != null ? aiDiagnosisService : new DeterministicFallbackAiDiagnosisProvider();
    }

    public RecoveryDecisionEngine() {
        this(new DeterministicFallbackAiDiagnosisProvider());
    }

    public RecoveryDecision decide(RecoveryCase recoveryCase) {
        if (recoveryCase == null) {
            throw new IllegalArgumentException("RecoveryCase must not be null");
        }

        PaymentAttempt attempt = recoveryCase.getPaymentAttempt();
        String rawReason = attempt != null && attempt.getFailureReason() != null ? attempt.getFailureReason() : "";
        FailureContext context = new FailureContext(
                recoveryCase.getId(),
                null,
                rawReason,
                recoveryCase.getFailureClass(),
                attempt != null ? attempt.getAmount() : null,
                attempt != null ? attempt.getCurrency() : null
        );

        // STEP 1: AI PROPOSES (Advisory diagnosis)
        AiDiagnosisResult aiDiagnosis = aiDiagnosisService.diagnose(context);
        log.info("[AI PROPOSAL] Case {}: proposedStrategy={}, confidence={}, explanation='{}'",
                recoveryCase.getId(),
                aiDiagnosis != null ? aiDiagnosis.suggestedStrategy() : "none",
                aiDiagnosis != null ? aiDiagnosis.confidence() : 0.0,
                aiDiagnosis != null ? aiDiagnosis.failureExplanation() : "none");

        // STEP 2: BACKEND VALIDATES (Authoritative domain and safety rules)
        if (!recoveryCase.isEligible()) {
            log.info("[BACKEND VALIDATION] Case {} is ineligible; enforcing ABSTAIN.", recoveryCase.getId());
            return new RecoveryDecision(
                    RecoveryStrategy.ABSTAIN,
                    0,
                    "Recovery case is marked ineligible; automated action is abstained.",
                    aiDiagnosis
            );
        }

        FailureClass failureClass = parseFailureClass(recoveryCase.getFailureClass());

        if (failureClass == FailureClass.HARD) {
            log.info("[BACKEND VALIDATION] Case {} is terminal HARD failure; enforcing ABSTAIN.", recoveryCase.getId());
            return new RecoveryDecision(
                    RecoveryStrategy.ABSTAIN,
                    0,
                    "Terminal hard failure detected; automated action is abstained.",
                    aiDiagnosis
            );
        }

        if (failureClass == FailureClass.UNKNOWN) {
            log.info("[BACKEND VALIDATION] Case {} is UNKNOWN anomaly; enforcing MANUAL_ESCALATE.", recoveryCase.getId());
            return new RecoveryDecision(
                    RecoveryStrategy.MANUAL_ESCALATE,
                    0,
                    "Unclassified failure context; escalated for manual/merchant review.",
                    aiDiagnosis
            );
        }

        // SOFT failure classification: evaluate AI advisory proposal vs deterministic baseline
        RecoveryStrategy finalStrategy;
        long delaySeconds;
        String finalReason;

        RecoveryStrategy aiStrategy = aiDiagnosis != null ? aiDiagnosis.suggestedStrategy() : null;
        Double confidence = aiDiagnosis != null ? aiDiagnosis.confidence() : null;

        // Backend validation of AI proposal for soft failures:
        // Must be a valid actionable soft recovery strategy with confidence >= 0.60
        if (aiStrategy != null && confidence != null && confidence >= 0.60
                && (aiStrategy == RecoveryStrategy.PAYMENT_LINK || aiStrategy == RecoveryStrategy.CUSTOMER_NUDGE || aiStrategy == RecoveryStrategy.SMART_RETRY)) {
            finalStrategy = aiStrategy;
            delaySeconds = calculateDelay(finalStrategy);
            finalReason = (aiDiagnosis.failureExplanation() != null && !aiDiagnosis.failureExplanation().isBlank())
                    ? aiDiagnosis.failureExplanation()
                    : "AI-validated recovery strategy: " + finalStrategy;
            log.info("[BACKEND VALIDATION] Case {}: Accepted AI strategy {} with confidence {}",
                    recoveryCase.getId(), finalStrategy, confidence);
        } else {
            // Deterministic fallback for soft failure
            String reason = rawReason.toUpperCase(Locale.ROOT);
            if (isCustomerDropoff(reason)) {
                finalStrategy = RecoveryStrategy.PAYMENT_LINK;
                delaySeconds = 60;
                finalReason = "Customer dropoff / 3DS authentication timeout detected; payment link recommended.";
            } else if (isCustomerActionRecoverable(reason)) {
                finalStrategy = RecoveryStrategy.CUSTOMER_NUDGE;
                delaySeconds = 180;
                finalReason = "Recoverable customer-action / OTP prompt detected; customer nudge recommended.";
            } else {
                finalStrategy = RecoveryStrategy.SMART_RETRY;
                delaySeconds = 300;
                finalReason = "Transient gateway/network/timeout failure detected; smart retry recommended.";
            }
            log.info("[BACKEND VALIDATION] Case {}: AI proposal unaccepted or low confidence; defaulted to deterministic strategy {}",
                    recoveryCase.getId(), finalStrategy);
        }

        return new RecoveryDecision(finalStrategy, delaySeconds, finalReason, aiDiagnosis);
    }

    private long calculateDelay(RecoveryStrategy strategy) {
        if (strategy == RecoveryStrategy.PAYMENT_LINK) {
            return 60;
        } else if (strategy == RecoveryStrategy.CUSTOMER_NUDGE) {
            return 180;
        } else if (strategy == RecoveryStrategy.SMART_RETRY) {
            return 300;
        }
        return 0;
    }

    private boolean isCustomerDropoff(String reason) {
        return reason.contains("AUTH_TIMEOUT")
                || reason.contains("AUTHENTICATION_TIMEOUT")
                || reason.contains("AUTHENTICATION TIMEOUT")
                || reason.contains("AUTH TIMEOUT")
                || reason.contains("3DS")
                || reason.contains("DROPOFF")
                || reason.contains("DROP_OFF")
                || reason.contains("USER_DROPPED")
                || reason.contains("USER DROPPED")
                || reason.contains("SESSION_EXPIRED")
                || reason.contains("SESSION EXPIRED")
                || reason.contains("PAGE_CLOSED")
                || reason.contains("PAGE CLOSED");
    }

    private boolean isCustomerActionRecoverable(String reason) {
        return reason.contains("INCORRECT_OTP")
                || reason.contains("INCORRECT OTP")
                || reason.contains("OTP_TIMEOUT")
                || reason.contains("OTP TIMEOUT")
                || reason.contains("OTP")
                || reason.contains("NUDGE")
                || reason.contains("USER PROMPT")
                || reason.contains("USER_PROMPT");
    }

    private FailureClass parseFailureClass(String failureClassStr) {
        if (failureClassStr == null) {
            return FailureClass.UNKNOWN;
        }
        try {
            return FailureClass.valueOf(failureClassStr.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return FailureClass.UNKNOWN;
        }
    }
}
