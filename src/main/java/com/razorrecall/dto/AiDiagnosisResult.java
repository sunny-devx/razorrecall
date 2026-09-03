package com.razorrecall.dto;

import com.razorrecall.domain.FailureClass;
import com.razorrecall.domain.RecoveryStrategy;

import java.util.Collections;
import java.util.List;

/**
 * Advisory recommendation produced by the AI diagnosis layer.
 * Note: Advisory only — final strategy and execution remain under authoritative backend guardrails.
 */
public record AiDiagnosisResult(
        String failureExplanation,
        FailureClass suggestedFailureClass,
        RecoveryStrategy suggestedStrategy,
        Double confidence,
        List<String> rationale,
        String providerName,
        boolean fallbackUsed
) {
    public AiDiagnosisResult {
        if (rationale == null) {
            rationale = Collections.emptyList();
        }
    }
}
