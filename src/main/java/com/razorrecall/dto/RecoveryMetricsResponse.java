package com.razorrecall.dto;

import java.math.BigDecimal;

public record RecoveryMetricsResponse(
        long totalCases,
        long recoveredCases,
        long actionPendingCases,
        long waitingForOutcomeCases,
        long abstainedCases,
        long escalatedCases,
        long failedCases,
        BigDecimal totalAtRiskAmount,
        BigDecimal totalRecoveredAmount,
        BigDecimal recoveryRatePercentage
) {}
