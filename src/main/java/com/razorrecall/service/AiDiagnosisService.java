package com.razorrecall.service;

import com.razorrecall.dto.AiDiagnosisResult;
import com.razorrecall.dto.FailureContext;

/**
 * Clean architectural boundary for advisory payment failure diagnosis.
 * Implementations may invoke external LLMs or local deterministic engines.
 */
public interface AiDiagnosisService {

    /**
     * Diagnoses a payment failure context and produces an advisory recommendation.
     *
     * @param context Payment failure metadata
     * @return Advisory AI diagnosis result
     */
    AiDiagnosisResult diagnose(FailureContext context);
}
