package com.razorrecall.service;

import com.razorrecall.dto.AiDiagnosisResult;
import com.razorrecall.dto.FailureContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

/**
 * Primary advisory AI diagnosis service.
 * Coordinates between external AI providers (Gemini) and the deterministic fallback engine.
 * Ensures that AI failures or unreachability never compromise system uptime or testability.
 */
@Service
@Primary
public class DefaultAiDiagnosisService implements AiDiagnosisService {

    private static final Logger log = LoggerFactory.getLogger(DefaultAiDiagnosisService.class);

    private final GeminiAiDiagnosisProvider geminiProvider;
    private final DeterministicFallbackAiDiagnosisProvider fallbackProvider;

    @Autowired
    public DefaultAiDiagnosisService(
            GeminiAiDiagnosisProvider geminiProvider,
            DeterministicFallbackAiDiagnosisProvider fallbackProvider
    ) {
        this.geminiProvider = geminiProvider;
        this.fallbackProvider = fallbackProvider;
    }

    @Override
    public AiDiagnosisResult diagnose(FailureContext context) {
        if (geminiProvider != null && geminiProvider.isAvailable()) {
            try {
                log.info("[AI PROPOSAL] Requesting AI diagnosis from Gemini for case: {}", context != null ? context.recoveryCaseId() : "unknown");
                AiDiagnosisResult result = geminiProvider.diagnose(context);

                // Reject if confidence is too low (< 0.60)
                if (result.confidence() == null || result.confidence() < 0.60) {
                    log.warn("[AI PROPOSAL] Gemini confidence too low ({}); falling back to deterministic engine.", result.confidence());
                    return fallbackProvider.diagnose(context);
                }

                return result;
            } catch (Exception e) {
                log.warn("[AI FALLBACK] External AI provider failed: {}. Engaging deterministic fallback.", e.getMessage());
                return fallbackProvider.diagnose(context);
            }
        }

        log.debug("[AI ADVISORY] AI provider not active; using deterministic rule diagnosis.");
        return fallbackProvider.diagnose(context);
    }
}
