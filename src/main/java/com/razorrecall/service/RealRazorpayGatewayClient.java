package com.razorrecall.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import com.razorrecall.dto.PaymentLinkRequest;
import com.razorrecall.dto.PaymentLinkResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

@Service
@ConditionalOnProperty(
        name = "razorrecall.razorpay.mode",
        havingValue = "test"
)
public class RealRazorpayGatewayClient implements RazorpayGatewayClient {

    private static final Logger log = LoggerFactory.getLogger(RealRazorpayGatewayClient.class);

    private final String keyId;
    private final String keySecret;
    private final String baseUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Autowired
    public RealRazorpayGatewayClient(
            @Value("${razorrecall.razorpay.key-id:}") String keyId,
            @Value("${razorrecall.razorpay.key-secret:}") String keySecret,
            @Value("${razorrecall.razorpay.base-url:https://api.razorpay.com}") String baseUrl,
            ObjectMapper objectMapper
    ) {
        this(keyId, keySecret, baseUrl, objectMapper, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build());
    }

    public RealRazorpayGatewayClient(
            String keyId,
            String keySecret,
            String baseUrl,
            ObjectMapper objectMapper,
            HttpClient httpClient
    ) {
        this.keyId = keyId != null ? keyId.trim() : "";
        this.keySecret = keySecret != null ? keySecret.trim() : "";
        this.baseUrl = baseUrl != null && !baseUrl.isBlank() ? baseUrl.replaceAll("/+$", "") : "https://api.razorpay.com";
        this.objectMapper = objectMapper != null ? objectMapper : JsonMapper.builder().build();
        this.httpClient = httpClient != null ? httpClient : HttpClient.newHttpClient();
    }

    @Override
    public PaymentLinkResponse createPaymentLink(PaymentLinkRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("PaymentLinkRequest must not be null");
        }

        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("amount", request.amount());
            root.put("currency", request.currency());
            if (request.description() != null && !request.description().isBlank()) {
                root.put("description", request.description());
            }
            if (request.referenceId() != null && !request.referenceId().isBlank()) {
                root.put("reference_id", request.referenceId());
                ObjectNode notes = root.putObject("notes");
                notes.put("reference_id", request.referenceId());
                notes.put("recovery_case_id", request.referenceId());
            }

            if ((request.customerPhone() != null && !request.customerPhone().isBlank()) ||
                    (request.customerEmail() != null && !request.customerEmail().isBlank())) {
                ObjectNode customer = root.putObject("customer");
                if (request.customerPhone() != null && !request.customerPhone().isBlank()) {
                    customer.put("contact", request.customerPhone());
                }
                if (request.customerEmail() != null && !request.customerEmail().isBlank()) {
                    customer.put("email", request.customerEmail());
                }
            }

            String jsonPayload = objectMapper.writeValueAsString(root);
            URI uri = URI.create(baseUrl + "/v1/payment_links");

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .header("Authorization", getBasicAuthHeader())
                    .POST(HttpRequest.BodyPublishers.ofString(jsonPayload, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            int statusCode = httpResponse.statusCode();
            String responseBody = httpResponse.body();

            if (statusCode < 200 || statusCode >= 300) {
                String safeError = extractErrorMessage(responseBody, statusCode);
                log.error("Razorpay Payment Links API error (HTTP {}): {}", statusCode, safeError);
                throw new RuntimeException("Razorpay gateway error (HTTP " + statusCode + "): " + safeError);
            }

            JsonNode respNode = objectMapper.readTree(responseBody);
            String id = respNode.path("id").asText();
            String shortUrl = respNode.path("short_url").asText();
            String status = respNode.path("status").asText("created");
            long amount = respNode.path("amount").asLong(request.amount());
            String currency = respNode.path("currency").asText(request.currency());
            String referenceId = respNode.hasNonNull("reference_id") ? respNode.path("reference_id").asText() : request.referenceId();
            long createdAt = respNode.path("created_at").asLong(Instant.now().getEpochSecond());

            return new PaymentLinkResponse(
                    id,
                    shortUrl,
                    status,
                    amount,
                    currency,
                    referenceId,
                    createdAt
            );

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Gateway request interrupted", e);
        } catch (IOException e) {
            log.error("Network communication failure with Razorpay gateway: {}", e.getMessage());
            throw new RuntimeException("Network error connecting to Razorpay gateway: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean verifyPaymentStatus(String paymentId) {
        if (paymentId == null || paymentId.isBlank()) {
            return false;
        }

        try {
            URI uri = URI.create(baseUrl + "/v1/payments/" + paymentId.trim());
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", getBasicAuthHeader())
                    .GET()
                    .build();

            HttpResponse<String> httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (httpResponse.statusCode() == 200) {
                JsonNode respNode = objectMapper.readTree(httpResponse.body());
                String status = respNode.path("status").asText("");
                return "captured".equalsIgnoreCase(status) || "authorized".equalsIgnoreCase(status);
            }
            return false;
        } catch (Exception e) {
            log.warn("Failed to verify payment status for payment ID {}: {}", paymentId, e.getMessage());
            return false;
        }
    }

    private String getBasicAuthHeader() {
        String credentials = keyId + ":" + keySecret;
        return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    private String extractErrorMessage(String responseBody, int statusCode) {
        if (responseBody == null || responseBody.isBlank()) {
            return "Empty response from gateway (HTTP " + statusCode + ")";
        }
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            if (root.has("error")) {
                JsonNode errorNode = root.get("error");
                if (errorNode.hasNonNull("description")) {
                    return errorNode.get("description").asText();
                }
            }
        } catch (Exception ignored) {}
        return "HTTP " + statusCode;
    }
}
