package com.razorrecall.service;

import com.razorrecall.domain.FailureClass;
import com.razorrecall.domain.PaymentAttempt;
import com.razorrecall.domain.RecoveryCase;
import com.razorrecall.domain.RecoveryStatus;
import com.razorrecall.domain.RecoveryStrategy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Set;

@Service
public class RecoveryGuardrailService {

    private static final Set<String> SUPPORTED_CURRENCIES = Set.of("INR", "USD");
    private static final Set<String> TERMINAL_STATUSES = Set.of(
            RecoveryStatus.RECOVERED.name(),
            RecoveryStatus.STOPPED.name(),
            RecoveryStatus.EXPIRED.name(),
            RecoveryStatus.ABSTAINED.name()
    );

    private final BigDecimal maxRecoveryAmount;
    private final Clock clock;

    public record GuardrailResult(
            boolean passed,
            RecoveryStatus targetStatus,
            String failureReason,
            OffsetDateTime nextActionAt
    ) {}

    @Autowired
    public RecoveryGuardrailService(
            @Value("${razorrecall.guardrails.max-recovery-amount:500000.00}") BigDecimal maxRecoveryAmount
    ) {
        this(maxRecoveryAmount, Clock.systemUTC());
    }

    public RecoveryGuardrailService(BigDecimal maxRecoveryAmount, Clock clock) {
        this.maxRecoveryAmount = maxRecoveryAmount != null ? maxRecoveryAmount : new BigDecimal("500000.00");
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    public GuardrailResult validate(RecoveryCase recoveryCase, RecoveryDecisionEngine.RecoveryDecision decision) {
        if (recoveryCase == null || decision == null) {
            throw new IllegalArgumentException("RecoveryCase and RecoveryDecision must not be null");
        }

        String currentStatus = recoveryCase.getStatus() != null
                ? recoveryCase.getStatus().trim().toUpperCase(Locale.ROOT)
                : "";

        // RULE 2: State Guardrail - Only DETECTED cases may be evaluated
        if (TERMINAL_STATUSES.contains(currentStatus) || !RecoveryStatus.DETECTED.name().equals(currentStatus)) {
            return new GuardrailResult(
                    false,
                    parseStatus(currentStatus),
                    "Evaluation rejected: Case is in terminal or invalid non-DETECTED status '" + currentStatus + "'",
                    null
            );
        }

        PaymentAttempt attempt = recoveryCase.getPaymentAttempt();

        // RULE 3: Amount Guardrail - must be > 0
        if (attempt != null && attempt.getAmount() != null) {
            BigDecimal amount = attempt.getAmount();
            if (amount.compareTo(BigDecimal.ZERO) <= 0) {
                return new GuardrailResult(
                        false,
                        RecoveryStatus.STOPPED,
                        "Amount guardrail failed: Amount must be strictly greater than zero.",
                        null
                );
            }

            // Amount threshold check
            if (amount.compareTo(maxRecoveryAmount) > 0) {
                return new GuardrailResult(
                        true,
                        RecoveryStatus.ESCALATED,
                        "Amount guardrail escalation: Amount " + amount + " exceeds configured threshold " + maxRecoveryAmount,
                        null
                );
            }
        }

        // RULE 4: Currency Guardrail - Supported: INR, USD
        if (attempt != null && attempt.getCurrency() != null) {
            String currency = attempt.getCurrency().trim().toUpperCase(Locale.ROOT);
            if (!SUPPORTED_CURRENCIES.contains(currency)) {
                return new GuardrailResult(
                        true,
                        RecoveryStatus.ESCALATED,
                        "Currency guardrail escalation: Unsupported currency '" + currency + "'",
                        null
                );
            }
        }

        // RULE 1: Eligibility & Hard Failure
        if (!recoveryCase.isEligible()
                || FailureClass.HARD.name().equalsIgnoreCase(recoveryCase.getFailureClass())
                || decision.strategy() == RecoveryStrategy.ABSTAIN) {
            return new GuardrailResult(
                    true,
                    RecoveryStatus.ABSTAINED,
                    "Eligibility guardrail: Ineligible or hard failure (" + decision.reason() + ")",
                    null
            );
        }

        // RULE 5: UNKNOWN / Anomaly / MANUAL_ESCALATE
        if (FailureClass.UNKNOWN.name().equalsIgnoreCase(recoveryCase.getFailureClass())
                || decision.strategy() == RecoveryStrategy.MANUAL_ESCALATE) {
            return new GuardrailResult(
                    true,
                    RecoveryStatus.ESCALATED,
                    "Anomaly guardrail: Unclassified failure or manual escalation requested (" + decision.reason() + ")",
                    null
            );
        }

        // RULE 6: Scheduling for valid automated decision
        OffsetDateTime nextActionAt = OffsetDateTime.now(clock).plusSeconds(Math.max(0, decision.suggestedDelaySeconds()));

        return new GuardrailResult(
                true,
                RecoveryStatus.ACTION_PENDING,
                null,
                nextActionAt
        );
    }

    private RecoveryStatus parseStatus(String statusStr) {
        try {
            return RecoveryStatus.valueOf(statusStr);
        } catch (Exception e) {
            return RecoveryStatus.DETECTED;
        }
    }
}
