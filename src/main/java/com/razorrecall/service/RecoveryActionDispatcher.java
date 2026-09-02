package com.razorrecall.service;

import com.razorrecall.domain.PaymentAttempt;
import com.razorrecall.domain.RecoveryCase;
import com.razorrecall.domain.RecoveryStatus;
import com.razorrecall.domain.RecoveryStrategy;
import com.razorrecall.dto.ActionExecutionResult;
import com.razorrecall.dto.PaymentLinkRequest;
import com.razorrecall.dto.PaymentLinkResponse;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Service
public class RecoveryActionDispatcher {

    private final RazorpayGatewayClient razorpayGatewayClient;

    public RecoveryActionDispatcher(RazorpayGatewayClient razorpayGatewayClient) {
        this.razorpayGatewayClient = razorpayGatewayClient;
    }

    public ActionExecutionResult dispatch(RecoveryCase recoveryCase, RecoveryStrategy strategy) {
        if (recoveryCase == null) {
            throw new IllegalArgumentException("RecoveryCase must not be null");
        }
        if (strategy == null) {
            throw new IllegalArgumentException("RecoveryStrategy must not be null");
        }

        if (strategy == RecoveryStrategy.ABSTAIN || strategy == RecoveryStrategy.MANUAL_ESCALATE) {
            throw new IllegalArgumentException("Cannot dispatch non-actionable recovery strategy: " + strategy);
        }

        PaymentAttempt attempt = recoveryCase.getPaymentAttempt();
        if (attempt == null) {
            throw new IllegalStateException("PaymentAttempt is missing for recovery case: " + recoveryCase.getId());
        }

        OffsetDateTime now = OffsetDateTime.now();

        long amountInPaise = convertToPaise(attempt.getAmount());
        String currency = attempt.getCurrency() != null ? attempt.getCurrency() : "INR";
        String caseIdStr = recoveryCase.getId().toString();

        switch (strategy) {
            case SMART_RETRY -> {
                String retryRef = "retry_" + caseIdStr.substring(0, 8);
                return new ActionExecutionResult(
                        recoveryCase.getId(),
                        strategy,
                        RecoveryStatus.WAITING_FOR_OUTCOME,
                        retryRef,
                        null,
                        now,
                        "Automated smart retry dispatched against payment gateway."
                );
            }
            case PAYMENT_LINK -> {
                PaymentLinkRequest linkRequest = new PaymentLinkRequest(
                        amountInPaise,
                        currency,
                        "Payment Recovery Link for Order " + (attempt.getOrderId() != null ? attempt.getOrderId() : caseIdStr),
                        caseIdStr,
                        null,
                        null
                );
                PaymentLinkResponse response = razorpayGatewayClient.createPaymentLink(linkRequest);
                return new ActionExecutionResult(
                        recoveryCase.getId(),
                        strategy,
                        RecoveryStatus.WAITING_FOR_OUTCOME,
                        response.id(),
                        response.shortUrl(),
                        now,
                        "Razorpay payment link generated successfully."
                );
            }
            case CUSTOMER_NUDGE -> {
                PaymentLinkRequest linkRequest = new PaymentLinkRequest(
                        amountInPaise,
                        currency,
                        "Complete your transaction: " + (attempt.getOrderId() != null ? attempt.getOrderId() : caseIdStr),
                        caseIdStr,
                        null,
                        null
                );
                PaymentLinkResponse response = razorpayGatewayClient.createPaymentLink(linkRequest);
                return new ActionExecutionResult(
                        recoveryCase.getId(),
                        strategy,
                        RecoveryStatus.WAITING_FOR_OUTCOME,
                        response.id(),
                        response.shortUrl(),
                        now,
                        "Customer recovery nudge sent with payment link."
                );
            }
            default -> throw new IllegalArgumentException("Unsupported recovery strategy: " + strategy);
        }
    }

    private long convertToPaise(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            return 100L; // Minimum 1 INR fallback
        }
        return amount.multiply(BigDecimal.valueOf(100)).longValue();
    }
}
