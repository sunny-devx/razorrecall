package com.razorrecall.service;

import com.razorrecall.domain.FailureClass;
import com.razorrecall.domain.RecoveryStrategy;
import com.razorrecall.dto.AiDiagnosisResult;
import com.razorrecall.dto.FailureContext;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * Built-in deterministic diagnosis provider.
 * Serves as the reliable, offline fallback whenever external AI models are unavailable,
 * timeout, return malformed output, or produce low-confidence recommendations.
 */
@Component
public class DeterministicFallbackAiDiagnosisProvider implements AiDiagnosisService {

    public static final String PROVIDER_NAME = "deterministic-rule-engine";

    @Override
    public AiDiagnosisResult diagnose(FailureContext context) {
        if (context == null) {
            return new AiDiagnosisResult(
                    "Null failure context received; defaulting to manual escalation.",
                    FailureClass.UNKNOWN,
                    RecoveryStrategy.MANUAL_ESCALATE,
                    0.50,
                    List.of("Missing failure metadata context"),
                    PROVIDER_NAME,
                    true
            );
        }

        String rawClass = context.failureClass() != null ? context.failureClass().toUpperCase(Locale.ROOT) : "";
        String reason = context.errorReason() != null ? context.errorReason().toUpperCase(Locale.ROOT) : "";
        String code = context.errorCode() != null ? context.errorCode().toUpperCase(Locale.ROOT) : "";
        String fullContext = (code + " " + reason).trim();

        if ("HARD".equals(rawClass) || isHardFailure(fullContext)) {
            return new AiDiagnosisResult(
                    "Terminal card or account failure detected. Transaction cannot succeed via automated re-attempts.",
                    FailureClass.HARD,
                    RecoveryStrategy.ABSTAIN,
                    0.99,
                    List.of(
                            "Issuing bank rejected transaction with non-recoverable terminal error code",
                            "Re-attempting charges on this credential may violate card network velocity rules",
                            "Merchant must abstain from automated recovery to protect gateway reputation"
                    ),
                    PROVIDER_NAME,
                    true
            );
        }

        if ("UNKNOWN".equals(rawClass) || (fullContext.isEmpty() && !"SOFT".equals(rawClass))) {
            return new AiDiagnosisResult(
                    "Unclassified failure context received; escalated for merchant or operations review.",
                    FailureClass.UNKNOWN,
                    RecoveryStrategy.MANUAL_ESCALATE,
                    0.50,
                    List.of(
                            "Error code or reason is unmapped in known gateway failure dictionaries",
                            "Manual investigation recommended to prevent automated customer disturbance"
                    ),
                    PROVIDER_NAME,
                    true
            );
        }

        // Soft failures
        if (isCustomerDropoff(fullContext)) {
            return new AiDiagnosisResult(
                    "Customer drop-off or 3DS authentication timeout detected. Transaction is recoverable via a direct Payment Link.",
                    FailureClass.SOFT,
                    RecoveryStrategy.PAYMENT_LINK,
                    0.95,
                    List.of(
                            "Authentication timed out before issuing bank verification was finalized",
                            "Customer demonstrated high purchase intent prior to 3DS challenge",
                            "Direct Razorpay Payment Link allows frictionless completion on customer's active device"
                    ),
                    PROVIDER_NAME,
                    true
            );
        }

        if (isCustomerActionRecoverable(fullContext)) {
            return new AiDiagnosisResult(
                    "Recoverable customer action or OTP delivery delay. Customer nudge notification recommended.",
                    FailureClass.SOFT,
                    RecoveryStrategy.CUSTOMER_NUDGE,
                    0.90,
                    List.of(
                            "Customer encountered temporary OTP challenge or verification friction",
                            "Card account remains active and capable of authentication",
                            "Immediate notification prompt encourages customer to complete the pending purchase"
                    ),
                    PROVIDER_NAME,
                    true
            );
        }

        return new AiDiagnosisResult(
                "Transient gateway, network, or bank rail timeout detected. Automated smart retry recommended.",
                FailureClass.SOFT,
                RecoveryStrategy.SMART_RETRY,
                0.88,
                List.of(
                        "Temporary timeout encountered during gateway processing",
                        "Failure is transient and expected to resolve after cooldown window",
                        "Smart retry preserves checkout conversion without disturbing the customer"
                ),
                PROVIDER_NAME,
                true
        );
    }

    private boolean isHardFailure(String text) {
        return text.contains("CARD_EXPIRED")
                || text.contains("CARD EXPIRED")
                || text.contains("EXPIRED_CARD")
                || text.contains("INSUFFICIENT_FUNDS")
                || text.contains("INSUFFICIENT FUNDS")
                || text.contains("SUSPECTED_FRAUD")
                || text.contains("DO_NOT_HONOR")
                || text.contains("STOLEN_CARD")
                || text.contains("LOST_CARD")
                || text.contains("ACCOUNT_CLOSED");
    }

    private boolean isCustomerDropoff(String text) {
        return text.contains("AUTH_TIMEOUT")
                || text.contains("AUTHENTICATION_TIMEOUT")
                || text.contains("AUTHENTICATION TIMEOUT")
                || text.contains("AUTH TIMEOUT")
                || text.contains("3DS")
                || text.contains("DROPOFF")
                || text.contains("DROP_OFF")
                || text.contains("USER_DROPPED")
                || text.contains("USER DROPPED")
                || text.contains("SESSION_EXPIRED")
                || text.contains("SESSION EXPIRED")
                || text.contains("PAGE_CLOSED")
                || text.contains("PAGE CLOSED");
    }

    private boolean isCustomerActionRecoverable(String text) {
        return text.contains("INCORRECT_OTP")
                || text.contains("INCORRECT OTP")
                || text.contains("OTP_TIMEOUT")
                || text.contains("OTP TIMEOUT")
                || text.contains("OTP")
                || text.contains("NUDGE")
                || text.contains("USER PROMPT")
                || text.contains("USER_PROMPT");
    }
}
