package com.razorrecall.service;

import com.razorrecall.domain.FailureClass;
import com.razorrecall.domain.RecoveryStrategy;
import com.razorrecall.dto.AiDiagnosisResult;
import com.razorrecall.dto.FailureContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AiDiagnosisServiceTest {

    private DeterministicFallbackAiDiagnosisProvider fallbackProvider;
    private GeminiAiDiagnosisProvider geminiProvider;

    @BeforeEach
    void setUp() {
        fallbackProvider = new DeterministicFallbackAiDiagnosisProvider();
        geminiProvider = new GeminiAiDiagnosisProvider(
                "",
                "gemini-2.0-flash",
                "https://generativelanguage.googleapis.com",
                3000,
                null,
                JsonMapper.builder().build()
        );
    }

    private FailureContext createContext(String code, String reason, String fClass) {
        return new FailureContext(
                UUID.randomUUID(),
                code,
                reason,
                fClass,
                new BigDecimal("2500.00"),
                "INR"
        );
    }

    @Test
    void testAiCredentialsAbsent_ReportsUnavailable() {
        assertFalse(geminiProvider.isAvailable());
        assertThrows(IllegalStateException.class, () -> geminiProvider.diagnose(createContext("TIMEOUT", "3DS", "SOFT")));
    }

    @Test
    void testDeterministicFallback_ValidDiagnosisForDropoff() {
        FailureContext ctx = createContext("AUTHENTICATION_TIMEOUT", "Customer drop off during 3DS", "SOFT");
        AiDiagnosisResult result = fallbackProvider.diagnose(ctx);

        assertNotNull(result);
        assertEquals(FailureClass.SOFT, result.suggestedFailureClass());
        assertEquals(RecoveryStrategy.PAYMENT_LINK, result.suggestedStrategy());
        assertTrue(result.confidence() >= 0.90);
        assertFalse(result.rationale().isEmpty());
        assertTrue(result.fallbackUsed());
        assertEquals(DeterministicFallbackAiDiagnosisProvider.PROVIDER_NAME, result.providerName());
    }

    @Test
    void testDeterministicFallback_ValidDiagnosisForOtpPrompt() {
        FailureContext ctx = createContext("INCORRECT_OTP", "User entered incorrect OTP", "SOFT");
        AiDiagnosisResult result = fallbackProvider.diagnose(ctx);

        assertEquals(FailureClass.SOFT, result.suggestedFailureClass());
        assertEquals(RecoveryStrategy.CUSTOMER_NUDGE, result.suggestedStrategy());
        assertTrue(result.confidence() >= 0.85);
    }

    @Test
    void testDeterministicFallback_ValidDiagnosisForHardFailure() {
        FailureContext ctx = createContext("CARD_EXPIRED", "Card expired", "HARD");
        AiDiagnosisResult result = fallbackProvider.diagnose(ctx);

        assertEquals(FailureClass.HARD, result.suggestedFailureClass());
        assertEquals(RecoveryStrategy.ABSTAIN, result.suggestedStrategy());
        assertTrue(result.confidence() >= 0.95);
    }

    @Test
    void testGeminiResponseParsing_ValidDiagnosis() throws IOException {
        String mockResponse = """
                {
                  "candidates": [
                    {
                      "content": {
                        "parts": [
                          {
                            "text": "{\\"failureExplanation\\": \\"Customer abandoned checkout during 3DS auth\\", \\"suggestedFailureClass\\": \\"SOFT\\", \\"suggestedStrategy\\": \\"PAYMENT_LINK\\", \\"confidence\\": 0.94, \\"rationale\\": [\\"Customer intent demonstrated\\", \\"3DS timeout\\"]}"
                          }
                        ]
                      }
                    }
                  ]
                }
                """;

        AiDiagnosisResult result = geminiProvider.parseAndValidateResponse(mockResponse);
        assertNotNull(result);
        assertEquals(FailureClass.SOFT, result.suggestedFailureClass());
        assertEquals(RecoveryStrategy.PAYMENT_LINK, result.suggestedStrategy());
        assertEquals(0.94, result.confidence(), 0.001);
        assertEquals(2, result.rationale().size());
        assertFalse(result.fallbackUsed());
    }

    @Test
    void testGeminiResponseParsing_MarkdownWrappedJson() throws IOException {
        String mockResponse = """
                {
                  "candidates": [
                    {
                      "content": {
                        "parts": [
                          {
                            "text": "```json\\n{\\"failureExplanation\\": \\"Gateway timeout\\", \\"suggestedFailureClass\\": \\"SOFT\\", \\"suggestedStrategy\\": \\"SMART_RETRY\\", \\"confidence\\": 0.85, \\"rationale\\": [\\"Transient error\\"]}\\n```"
                          }
                        ]
                      }
                    }
                  ]
                }
                """;

        AiDiagnosisResult result = geminiProvider.parseAndValidateResponse(mockResponse);
        assertNotNull(result);
        assertEquals(RecoveryStrategy.SMART_RETRY, result.suggestedStrategy());
        assertEquals(0.85, result.confidence(), 0.001);
    }

    @Test
    void testGeminiResponseParsing_InvalidConfidenceHigh_ThrowsException() {
        String mockResponse = """
                {
                  "candidates": [
                    {
                      "content": {
                        "parts": [
                          {
                            "text": "{\\"failureExplanation\\": \\"Too confident\\", \\"suggestedFailureClass\\": \\"SOFT\\", \\"suggestedStrategy\\": \\"PAYMENT_LINK\\", \\"confidence\\": 1.5}"
                          }
                        ]
                      }
                    }
                  ]
                }
                """;

        assertThrows(IllegalArgumentException.class, () -> geminiProvider.parseAndValidateResponse(mockResponse));
    }

    @Test
    void testGeminiResponseParsing_InvalidConfidenceNegative_ThrowsException() {
        String mockResponse = """
                {
                  "candidates": [
                    {
                      "content": {
                        "parts": [
                          {
                            "text": "{\\"failureExplanation\\": \\"Negative confidence\\", \\"suggestedFailureClass\\": \\"SOFT\\", \\"suggestedStrategy\\": \\"PAYMENT_LINK\\", \\"confidence\\": -0.2}"
                          }
                        ]
                      }
                    }
                  ]
                }
                """;

        assertThrows(IllegalArgumentException.class, () -> geminiProvider.parseAndValidateResponse(mockResponse));
    }

    @Test
    void testGeminiResponseParsing_MalformedOutput_ThrowsException() {
        String mockResponse = """
                {
                  "candidates": [
                    {
                      "content": {
                        "parts": [
                          {
                            "text": "This is plain text without valid JSON"
                          }
                        ]
                      }
                    }
                  ]
                }
                """;

        assertThrows(Exception.class, () -> geminiProvider.parseAndValidateResponse(mockResponse));
    }

    @Test
    void testDefaultAiDiagnosisService_FallsBackWhenProviderThrows() {
        DefaultAiDiagnosisService service = new DefaultAiDiagnosisService(geminiProvider, fallbackProvider);
        FailureContext ctx = createContext("GATEWAY_TIMEOUT", "Bank gateway error", "SOFT");

        AiDiagnosisResult result = service.diagnose(ctx);
        assertNotNull(result);
        assertTrue(result.fallbackUsed());
        assertEquals(RecoveryStrategy.SMART_RETRY, result.suggestedStrategy());
        assertEquals(DeterministicFallbackAiDiagnosisProvider.PROVIDER_NAME, result.providerName());
    }
}
