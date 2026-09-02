package com.razorrecall.service;

import com.razorrecall.dto.PaymentLinkRequest;
import com.razorrecall.dto.PaymentLinkResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RazorpayGatewayClientTest {

    private RazorpayGatewayClient client;

    @BeforeEach
    void setUp() {
        client = new MockRazorpayGatewayClient();
    }

    @Test
    void testCreatePaymentLink_Success() {
        PaymentLinkRequest request = new PaymentLinkRequest(
                250000L,
                "INR",
                "Recovery payment link for order_123",
                "case_ref_001",
                null,
                null
        );

        PaymentLinkResponse response = client.createPaymentLink(request);

        assertNotNull(response);
        assertTrue(response.id().startsWith("plink_"));
        assertTrue(response.shortUrl().startsWith("https://rzp.io/i/"));
        assertEquals("created", response.status());
        assertEquals(250000L, response.amount());
        assertEquals("INR", response.currency());
        assertEquals("case_ref_001", response.referenceId());
        assertTrue(response.createdAt() > 0);
    }

    @Test
    void testVerifyPaymentStatus() {
        assertTrue(client.verifyPaymentStatus("pay_12345"));
        assertFalse(client.verifyPaymentStatus(""));
        assertFalse(client.verifyPaymentStatus(null));
    }
}
