package com.razorrecall.service;

import com.razorrecall.domain.FailureClass;
import com.razorrecall.domain.RecoveryStrategy;
import com.razorrecall.dto.AiDiagnosisResult;
import com.razorrecall.dto.FailureContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Production-like AI diagnosis provider powered by Google Gemini.
 * Sends minimal, non-sensitive failure metadata and receives structured advisory recommendations.
 */
@Component
public class GeminiAiDiagnosisProvider {

    private static final Logger log = LoggerFactory.getLogger(GeminiAiDiagnosisProvider.class);
    public static final String PROVIDER_NAME = "gemini-ai";

    private final String apiKey;
    private final String model;
    private final String baseUrl;
    private final int timeoutMs;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Autowired
    public GeminiAiDiagnosisProvider(
            @Value("${razorrecall.ai.gemini.api-key:}") String apiKey,
            @Value("${razorrecall.ai.gemini.model:gemini-2.0-flash}") String model,
            @Value("${razorrecall.ai.gemini.base-url:https://generativelanguage.googleapis.com}") String baseUrl,
            @Value("${razorrecall.ai.gemini.timeout-ms:3000}") int timeoutMs
    ) {
        this(apiKey, model, baseUrl, timeoutMs, HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(timeoutMs))
                .build(), JsonMapper.builder().build());
    }

    public GeminiAiDiagnosisProvider(
            String apiKey,
            String model,
            String baseUrl,
            int timeoutMs,
            HttpClient httpClient,
            ObjectMapper objectMapper
    ) {
        this.apiKey = apiKey != null ? apiKey.trim() : "";
        this.model = model != null && !model.isBlank() ? model.trim() : "gemini-2.0-flash";
        this.baseUrl = baseUrl != null && !baseUrl.isBlank() ? baseUrl.trim() : "https://generativelanguage.googleapis.com";
        this.timeoutMs = timeoutMs > 0 ? timeoutMs : 3000;
        this.httpClient = httpClient != null ? httpClient : HttpClient.newHttpClient();
        this.objectMapper = objectMapper != null ? objectMapper : JsonMapper.builder().build();
    }

    public boolean isAvailable() {
        return !apiKey.isEmpty();
    }

    public AiDiagnosisResult diagnose(FailureContext context) {
        if (!isAvailable()) {
            throw new IllegalStateException("Gemini API key is not configured or is empty.");
        }

        try {
            String prompt = buildPrompt(context);
            String requestBody = buildRequestBody(prompt);

            String url = String.format("%s/v1beta/models/%s:generateContent?key=%s",
                    baseUrl, model, apiKey);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            if (response.statusCode() != 200) {
                throw new IOException("Gemini API responded with HTTP " + response.statusCode() + ": " + response.body());
            }

            return parseAndValidateResponse(response.body());

        } catch (Exception e) {
            log.warn("Gemini AI diagnosis failed or timed out: {}", e.getMessage());
            throw new RuntimeException("Gemini diagnosis failed: " + e.getMessage(), e);
        }
    }

    private String buildPrompt(FailureContext context) {
        return "You are an autonomous payment failure recovery intelligence engine for Razorpay.\n"
                + "Diagnose the following payment failure and recommend an advisory recovery strategy.\n"
                + "Failure Metadata:\n"
                + "- Error Code: " + (context.errorCode() != null ? context.errorCode() : "N/A") + "\n"
                + "- Error Reason: " + (context.errorReason() != null ? context.errorReason() : "N/A") + "\n"
                + "- Preliminary Class: " + (context.failureClass() != null ? context.failureClass() : "UNKNOWN") + "\n"
                + "- Amount: " + (context.amount() != null ? context.amount().toPlainString() : "0.00") + " " + (context.currency() != null ? context.currency() : "INR") + "\n\n"
                + "Rules:\n"
                + "- suggestedFailureClass must be one of: SOFT, HARD, UNKNOWN\n"
                + "- suggestedStrategy must be one of: PAYMENT_LINK, SMART_RETRY, CUSTOMER_NUDGE, ABSTAIN, MANUAL_ESCALATE\n"
                + "- confidence must be a number between 0.0 and 1.0\n"
                + "- Output ONLY a raw JSON object with keys: failureExplanation, suggestedFailureClass, suggestedStrategy, confidence, rationale (array of strings).\n";
    }

    private String buildRequestBody(String prompt) {
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode contents = root.putArray("contents");
        ObjectNode content = contents.addObject();
        ArrayNode parts = content.putArray("parts");
        parts.addObject().put("text", prompt);

        ObjectNode generationConfig = root.putObject("generationConfig");
        generationConfig.put("responseMimeType", "application/json");
        generationConfig.put("temperature", 0.1);

        return root.toString();
    }

    public AiDiagnosisResult parseAndValidateResponse(String responseJson) throws IOException {
        JsonNode root = objectMapper.readTree(responseJson);
        JsonNode candidates = root.path("candidates");
        if (candidates.isMissingNode() || !candidates.isArray() || candidates.isEmpty()) {
            throw new IllegalArgumentException("Gemini response contains no candidates");
        }

        JsonNode textNode = candidates.get(0).path("content").path("parts").get(0).path("text");
        if (textNode.isMissingNode() || textNode.asText().isBlank()) {
            throw new IllegalArgumentException("Gemini response contains no text content");
        }

        String rawContent = textNode.asText().trim();
        // Strip markdown code fences if present
        if (rawContent.startsWith("```json")) {
            rawContent = rawContent.substring(7);
        } else if (rawContent.startsWith("```")) {
            rawContent = rawContent.substring(3);
        }
        if (rawContent.endsWith("```")) {
            rawContent = rawContent.substring(0, rawContent.length() - 3);
        }
        rawContent = rawContent.trim();

        JsonNode diagNode = objectMapper.readTree(rawContent);

        String explanation = diagNode.path("failureExplanation").asText(null);
        if (explanation == null || explanation.isBlank()) {
            throw new IllegalArgumentException("AI response missing 'failureExplanation'");
        }

        String rawClass = diagNode.path("suggestedFailureClass").asText(null);
        FailureClass failureClass;
        try {
            failureClass = rawClass != null ? FailureClass.valueOf(rawClass.trim().toUpperCase(Locale.ROOT)) : null;
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid suggestedFailureClass from AI: " + rawClass);
        }
        if (failureClass == null) {
            throw new IllegalArgumentException("AI response missing 'suggestedFailureClass'");
        }

        String rawStrategy = diagNode.path("suggestedStrategy").asText(null);
        RecoveryStrategy strategy;
        try {
            strategy = rawStrategy != null ? RecoveryStrategy.valueOf(rawStrategy.trim().toUpperCase(Locale.ROOT)) : null;
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid suggestedStrategy from AI: " + rawStrategy);
        }
        if (strategy == null) {
            throw new IllegalArgumentException("AI response missing 'suggestedStrategy'");
        }

        JsonNode confNode = diagNode.path("confidence");
        if (confNode.isMissingNode() || !confNode.isNumber()) {
            throw new IllegalArgumentException("AI response missing valid numeric 'confidence'");
        }
        double confidence = confNode.asDouble();
        if (confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("AI confidence out of range [0.0, 1.0]: " + confidence);
        }

        List<String> rationale = new ArrayList<>();
        JsonNode rationaleNode = diagNode.path("rationale");
        if (rationaleNode.isArray()) {
            for (JsonNode r : rationaleNode) {
                if (r.isTextual() && !r.asText().isBlank()) {
                    rationale.add(r.asText().trim());
                }
            }
        }

        return new AiDiagnosisResult(
                explanation,
                failureClass,
                strategy,
                confidence,
                rationale,
                PROVIDER_NAME,
                false
        );
    }
}
