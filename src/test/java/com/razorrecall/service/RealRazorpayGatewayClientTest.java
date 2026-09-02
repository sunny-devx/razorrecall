package com.razorrecall.service;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.razorrecall.dto.PaymentLinkRequest;
import com.razorrecall.dto.PaymentLinkResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class RealRazorpayGatewayClientTest {

    private static final String TEST_KEY_ID = "rzp_test_key_123456";
    private static final String TEST_KEY_SECRET = "super_secret_value_xyz789";

    private HttpServer server;
    private int port;
    private RealRazorpayGatewayClient client;
    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        port = server.getAddress().getPort();
        server.start();

        client = new RealRazorpayGatewayClient(
                TEST_KEY_ID,
                TEST_KEY_SECRET,
                "http://localhost:" + port,
                objectMapper,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
        );
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void testCreatePaymentLink_Success_ValidatesMethodPathHeadersBody() {
        AtomicReference<String> capturedMethod = new AtomicReference<>();
        AtomicReference<String> capturedAuth = new AtomicReference<>();
        AtomicReference<String> capturedContentType = new AtomicReference<>();
        AtomicReference<String> capturedBody = new AtomicReference<>();

        server.createContext("/v1/payment_links", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                capturedMethod.set(exchange.getRequestMethod());
                capturedAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
                capturedContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));

                try (InputStream is = exchange.getRequestBody()) {
                    capturedBody.set(new String(is.readAllBytes(), StandardCharsets.UTF_8));
                }

                String jsonResponse = """
                    {
                      "id": "plink_real_test_abc123",
                      "short_url": "https://rzp.io/i/testAbc",
                      "status": "created",
                      "amount": 350000,
                      "currency": "INR",
                      "reference_id": "case_uuid_001",
                      "created_at": 1712345678
                    }
                    """;
                byte[] responseBytes = jsonResponse.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, responseBytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(responseBytes);
                }
            }
        });

        PaymentLinkRequest request = new PaymentLinkRequest(
                350000L,
                "INR",
                "Recovery link for order_999",
                "case_uuid_001",
                "+919876543210",
                "customer@example.com"
        );

        PaymentLinkResponse response = client.createPaymentLink(request);

        // 1. Verify HTTP method & path
        assertEquals("POST", capturedMethod.get());

        // 2. Verify Basic Authentication header
        String expectedBasicAuth = "Basic " + Base64.getEncoder().encodeToString((TEST_KEY_ID + ":" + TEST_KEY_SECRET).getBytes(StandardCharsets.UTF_8));
        assertEquals(expectedBasicAuth, capturedAuth.get());

        // 3. Verify Content-Type header
        assertEquals("application/json", capturedContentType.get());

        // 4. Verify JSON payload structure & fields
        assertNotNull(capturedBody.get());
        JsonNode requestJson = assertDoesNotThrow(() -> objectMapper.readTree(capturedBody.get()));
        assertEquals(350000L, requestJson.get("amount").asLong());
        assertEquals("INR", requestJson.get("currency").asText());
        assertEquals("Recovery link for order_999", requestJson.get("description").asText());
        assertEquals("case_uuid_001", requestJson.get("reference_id").asText());
        assertEquals("case_uuid_001", requestJson.get("notes").get("reference_id").asText());
        assertEquals("case_uuid_001", requestJson.get("notes").get("recovery_case_id").asText());
        assertEquals("+919876543210", requestJson.get("customer").get("contact").asText());
        assertEquals("customer@example.com", requestJson.get("customer").get("email").asText());

        // 5. Verify response parsing
        assertNotNull(response);
        assertEquals("plink_real_test_abc123", response.id());
        assertEquals("https://rzp.io/i/testAbc", response.shortUrl());
        assertEquals("created", response.status());
        assertEquals(350000L, response.amount());
        assertEquals("INR", response.currency());
        assertEquals("case_uuid_001", response.referenceId());
        assertEquals(1712345678L, response.createdAt());
    }

    @Test
    void testCreatePaymentLink_ApiError_ThrowsException_WithoutSecretLeakage() {
        server.createContext("/v1/payment_links", exchange -> {
            String errorJson = """
                {
                  "error": {
                    "code": "BAD_REQUEST_ERROR",
                    "description": "Amount must be at least 100 paise"
                  }
                }
                """;
            byte[] bytes = errorJson.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(400, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });

        PaymentLinkRequest request = new PaymentLinkRequest(
                50L,
                "INR",
                "Invalid amount",
                "case_002",
                null,
                null
        );

        RuntimeException ex = assertThrows(RuntimeException.class, () -> client.createPaymentLink(request));

        assertTrue(ex.getMessage().contains("Amount must be at least 100 paise"));
        assertTrue(ex.getMessage().contains("HTTP 400"));

        // Crucial security requirement: Key secret and Authorization header must NOT be leaked
        assertFalse(ex.getMessage().contains(TEST_KEY_SECRET));
        assertFalse(ex.getMessage().contains("Basic "));
    }

    @Test
    void testCreatePaymentLink_ServerError500_ThrowsException() {
        server.createContext("/v1/payment_links", exchange -> {
            byte[] bytes = "Internal Gateway Failure".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });

        PaymentLinkRequest request = new PaymentLinkRequest(
                100000L,
                "INR",
                "Timeout test",
                "case_003",
                null,
                null
        );

        RuntimeException ex = assertThrows(RuntimeException.class, () -> client.createPaymentLink(request));
        assertTrue(ex.getMessage().contains("500"));
        assertFalse(ex.getMessage().contains(TEST_KEY_SECRET));
    }

    @Test
    void testVerifyPaymentStatus_CapturedReturnsTrue() {
        AtomicReference<String> capturedAuth = new AtomicReference<>();

        server.createContext("/v1/payments/pay_test_999", exchange -> {
            capturedAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            String json = """
                {
                  "id": "pay_test_999",
                  "status": "captured",
                  "amount": 250000
                }
                """;
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });

        boolean result = client.verifyPaymentStatus("pay_test_999");
        assertTrue(result);

        String expectedBasicAuth = "Basic " + Base64.getEncoder().encodeToString((TEST_KEY_ID + ":" + TEST_KEY_SECRET).getBytes(StandardCharsets.UTF_8));
        assertEquals(expectedBasicAuth, capturedAuth.get());
    }

    @Test
    void testVerifyPaymentStatus_FailedOrNon200ReturnsFalse() {
        server.createContext("/v1/payments/pay_failed_001", exchange -> {
            String json = """
                {
                  "id": "pay_failed_001",
                  "status": "failed"
                }
                """;
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });

        server.createContext("/v1/payments/pay_404", exchange -> {
            exchange.sendResponseHeaders(404, 0);
            exchange.close();
        });

        assertFalse(client.verifyPaymentStatus("pay_failed_001"));
        assertFalse(client.verifyPaymentStatus("pay_404"));
        assertFalse(client.verifyPaymentStatus(null));
        assertFalse(client.verifyPaymentStatus(""));
    }
}
