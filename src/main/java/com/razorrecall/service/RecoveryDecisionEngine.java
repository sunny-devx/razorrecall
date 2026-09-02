package com.razorrecall.service;

import com.razorrecall.domain.FailureClass;
import com.razorrecall.domain.PaymentAttempt;
import com.razorrecall.domain.RecoveryCase;
import com.razorrecall.domain.RecoveryStrategy;
import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
public class RecoveryDecisionEngine {

    public record RecoveryDecision(
            RecoveryStrategy strategy,
            long suggestedDelaySeconds,
            String reason
    ) {}

    public RecoveryDecision decide(RecoveryCase recoveryCase) {
        if (recoveryCase == null) {
            throw new IllegalArgumentException("RecoveryCase must not be null");
        }

        if (!recoveryCase.isEligible()) {
            return new RecoveryDecision(
                    RecoveryStrategy.ABSTAIN,
                    0,
                    "Recovery case is marked ineligible; automated action is abstained."
            );
        }

        FailureClass failureClass = parseFailureClass(recoveryCase.getFailureClass());

        if (failureClass == FailureClass.HARD) {
            return new RecoveryDecision(
                    RecoveryStrategy.ABSTAIN,
                    0,
                    "Terminal hard failure detected; automated action is abstained."
            );
        }

        if (failureClass == FailureClass.UNKNOWN) {
            return new RecoveryDecision(
                    RecoveryStrategy.MANUAL_ESCALATE,
                    0,
                    "Unclassified failure context; escalated for manual/merchant review."
            );
        }

        // SOFT failure classification: inspect failure reason from payment attempt
        PaymentAttempt attempt = recoveryCase.getPaymentAttempt();
        String reason = attempt != null && attempt.getFailureReason() != null
                ? attempt.getFailureReason().toUpperCase(Locale.ROOT)
                : "";

        if (isCustomerDropoff(reason)) {
            return new RecoveryDecision(
                    RecoveryStrategy.PAYMENT_LINK,
                    60,
                    "Customer dropoff / 3DS authentication timeout detected; payment link recommended."
            );
        }

        if (isCustomerActionRecoverable(reason)) {
            return new RecoveryDecision(
                    RecoveryStrategy.CUSTOMER_NUDGE,
                    180,
                    "Recoverable customer-action / OTP prompt detected; customer nudge recommended."
            );
        }

        // Default soft failure: transient gateway/network/timeout
        return new RecoveryDecision(
                RecoveryStrategy.SMART_RETRY,
                300,
                "Transient gateway/network/timeout failure detected; smart retry recommended."
        );
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
