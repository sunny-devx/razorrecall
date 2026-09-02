package com.razorrecall.service;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class RazorpayGatewayConditionalSelectionTest {

    @Nested
    @SpringBootTest(properties = {
            "razorrecall.razorpay.mode=mock",
            "razorrecall.webhook.secret=test_webhook_secret_key_12345"
    })
    class MockModeTest {

        @Autowired
        private RazorpayGatewayClient gatewayClient;

        @Test
        void testResolvesMockRazorpayGatewayClient() {
            assertNotNull(gatewayClient);
            assertInstanceOf(MockRazorpayGatewayClient.class, gatewayClient);
        }
    }

    @Nested
    @SpringBootTest(properties = {
            "razorrecall.razorpay.mode=test",
            "razorrecall.razorpay.key-id=rzp_test_testkey",
            "razorrecall.razorpay.key-secret=testsecret",
            "razorrecall.webhook.secret=test_webhook_secret_key_12345"
    })
    class TestModeTest {

        @Autowired
        private RazorpayGatewayClient gatewayClient;

        @Test
        void testResolvesRealRazorpayGatewayClient() {
            assertNotNull(gatewayClient);
            assertInstanceOf(RealRazorpayGatewayClient.class, gatewayClient);
        }
    }
}
