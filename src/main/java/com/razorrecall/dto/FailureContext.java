package com.razorrecall.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Contextual payment failure details passed to the AI diagnosis boundary.
 * Contains only non-sensitive diagnostic failure metadata.
 */
public record FailureContext(
        UUID recoveryCaseId,
        String errorCode,
        String errorReason,
        String failureClass,
        BigDecimal amount,
        String currency
) {}
