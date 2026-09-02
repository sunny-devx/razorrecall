package com.razorrecall.dto;

import com.razorrecall.domain.RecoveryStatus;
import com.razorrecall.domain.RecoveryStrategy;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ActionExecutionResult(
        UUID recoveryCaseId,
        RecoveryStrategy strategy,
        RecoveryStatus targetStatus,
        String actionReference,
        String actionUrl,
        OffsetDateTime executedAt,
        String message
) {}
