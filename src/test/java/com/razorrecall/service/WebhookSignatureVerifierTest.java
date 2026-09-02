package com.razorrecall.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WebhookSignatureVerifierTest {

    private WebhookSignatureVerifier verifier;
    private final String secret = "test_secret_key_abcdef123456";

    @BeforeEach
    void setUp() {
        verifier = new WebhookSignatureVerifier();
    }

    @Test
    void testValidSignatureVerification() {
        String payload = "{\"event\":\"payment.failed\",\"id\":\"pay_123\"}";
        String signature = verifier.calculateHmacSha256(payload, secret);

        assertNotNull(signature);
        assertTrue(verifier.verify(payload, signature, secret));
    }

    @Test
    void testTamperedPayloadFailsVerification() {
        String originalPayload = "{\"event\":\"payment.failed\",\"amount\":1000}";
        String tamperedPayload = "{\"event\":\"payment.failed\",\"amount\":2000}";

        String signature = verifier.calculateHmacSha256(originalPayload, secret);

        assertFalse(verifier.verify(tamperedPayload, signature, secret));
    }

    @Test
    void testTamperedSignatureFailsVerification() {
        String payload = "{\"event\":\"payment.failed\",\"id\":\"pay_123\"}";
        String validSignature = verifier.calculateHmacSha256(payload, secret);
        String corruptedSignature = validSignature.substring(0, validSignature.length() - 2) + "00";

        assertFalse(verifier.verify(payload, corruptedSignature, secret));
    }

    @Test
    void testWrongSecretFailsVerification() {
        String payload = "{\"event\":\"payment.failed\",\"id\":\"pay_123\"}";
        String signatureWithSecretA = verifier.calculateHmacSha256(payload, "secret_A");

        assertFalse(verifier.verify(payload, signatureWithSecretA, "secret_B"));
    }

    @Test
    void testNullAndEmptyInputsHandledSafely() {
        assertFalse(verifier.verify(null, "some_sig", secret));
        assertFalse(verifier.verify("payload", null, secret));
        assertFalse(verifier.verify("payload", "some_sig", null));
        assertFalse(verifier.verify("", "some_sig", secret));
        assertFalse(verifier.verify("payload", "", secret));
        assertFalse(verifier.verify("payload", "some_sig", ""));
    }

    @Test
    void testConstantTimeComparison() {
        String sig1 = "a1b2c3d4e5f6";
        String sig2 = "a1b2c3d4e5f6";
        String sig3 = "a1b2c3d4e500";

        assertTrue(verifier.secureCompare(sig1, sig2));
        assertFalse(verifier.secureCompare(sig1, sig3));
        assertFalse(verifier.secureCompare(null, sig1));
        assertFalse(verifier.secureCompare(sig1, null));
    }
}
