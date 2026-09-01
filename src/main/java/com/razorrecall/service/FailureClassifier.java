package com.razorrecall.service;

import com.razorrecall.domain.FailureClass;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

@Component
public class FailureClassifier {

    private static final Set<String> SOFT_ERROR_CODES = Set.of(
            "GATEWAY_ERROR",
            "GATEWAY_TIMEOUT",
            "BANK_TECHNICAL_ERROR",
            "BANK_DOWNTIME",
            "NETWORK_ERROR",
            "TIMEOUT",
            "PAYMENT_TIMED_OUT",
            "SERVER_ERROR",
            "INTERNAL_SERVER_ERROR",
            "AUTHENTICATION_TIMEOUT",
            "BAD_GATEWAY",
            "SERVICE_UNAVAILABLE"
    );

    private static final Set<String> HARD_ERROR_CODES = Set.of(
            "CARD_EXPIRED",
            "EXPIRED_CARD",
            "INVALID_CARD_NUMBER",
            "INVALID_CVV",
            "INSUFFICIENT_FUNDS",
            "SUSPECTED_FRAUD",
            "FRAUD_DETECTED",
            "DO_NOT_HONOR",
            "CUSTOMER_CANCELLED",
            "TRANSACTION_NOT_PERMITTED",
            "CARD_INACTIVE",
            "CARD_BLOCKED",
            "LOST_CARD",
            "STOLEN_CARD",
            "PAYMENT_DECLINED"
    );

    public record ClassificationResult(FailureClass failureClass, boolean eligible, String reason) {
    }

    public ClassificationResult classify(String errorCode, String errorReason) {
        String normalizedCode = normalize(errorCode);
        String normalizedReason = normalize(errorReason);

        if (isSoftFailure(normalizedCode, normalizedReason)) {
            return new ClassificationResult(FailureClass.SOFT, true, "Transient failure detected; eligible for retry/recovery.");
        }

        if (isHardFailure(normalizedCode, normalizedReason)) {
            return new ClassificationResult(FailureClass.HARD, false, "Permanent terminal failure; not eligible for automated retry.");
        }

        return new ClassificationResult(FailureClass.UNKNOWN, false, "Unrecognized failure code/reason; requires manual/cautious review.");
    }

    private boolean isSoftFailure(String code, String reason) {
        if (SOFT_ERROR_CODES.contains(code)) {
            return true;
        }
        return reason.contains("TIMEOUT")
                || reason.contains("NETWORK")
                || reason.contains("GATEWAY")
                || reason.contains("TECHNICAL")
                || reason.contains("TEMPORARY")
                || reason.contains("BANK UNAVAILABLE");
    }

    private boolean isHardFailure(String code, String reason) {
        if (HARD_ERROR_CODES.contains(code)) {
            return true;
        }
        return reason.contains("EXPIRED")
                || reason.contains("INSUFFICIENT")
                || reason.contains("FRAUD")
                || reason.contains("INVALID CARD")
                || reason.contains("DO NOT HONOR")
                || reason.contains("BLOCKED")
                || reason.contains("STOLEN")
                || reason.contains("LOST CARD")
                || reason.contains("CANCELLED BY USER")
                || reason.contains("CANCELLED BY CUSTOMER");
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }
}
