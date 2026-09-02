package com.razorrecall.service;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Component
public class WebhookSignatureVerifier {

    private static final String HMAC_SHA256_ALGORITHM = "HmacSHA256";

    /**
     * Verifies the provided HMAC-SHA256 signature against the raw HTTP request body bytes and merchant secret.
     * Uses constant-time equality check via MessageDigest.isEqual.
     *
     * @param rawPayload      Exact raw HTTP payload received
     * @param signatureHeader Value from X-Razorpay-Signature header
     * @param secret          Merchant-specific or platform webhook secret
     * @return true if valid, false otherwise
     */
    public boolean verify(String rawPayload, String signatureHeader, String secret) {
        if (rawPayload == null || signatureHeader == null || secret == null) {
            return false;
        }

        if (rawPayload.isBlank() || signatureHeader.isBlank() || secret.isBlank()) {
            return false;
        }

        try {
            String expectedSignature = calculateHmacSha256(rawPayload, secret);
            return secureCompare(signatureHeader.trim(), expectedSignature);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Computes the HMAC-SHA256 digest of raw payload using the provided secret key.
     * Output is lowercase hexadecimal string.
     */
    public String calculateHmacSha256(String rawPayload, String secret) {
        if (rawPayload == null || secret == null) {
            throw new IllegalArgumentException("Payload and secret must not be null");
        }

        try {
            Mac mac = Mac.getInstance(HMAC_SHA256_ALGORITHM);
            SecretKeySpec secretKeySpec = new SecretKeySpec(
                    secret.getBytes(StandardCharsets.UTF_8),
                    HMAC_SHA256_ALGORITHM
            );
            mac.init(secretKeySpec);

            byte[] hashBytes = mac.doFinal(rawPayload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashBytes);

        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("Failed to compute HMAC-SHA256 signature", e);
        }
    }

    /**
     * Performs a constant-time comparison between actual and expected signatures to prevent timing attacks.
     */
    public boolean secureCompare(String actualSignature, String expectedSignature) {
        if (actualSignature == null || expectedSignature == null) {
            return false;
        }

        byte[] actualBytes = actualSignature.getBytes(StandardCharsets.UTF_8);
        byte[] expectedBytes = expectedSignature.getBytes(StandardCharsets.UTF_8);

        return MessageDigest.isEqual(actualBytes, expectedBytes);
    }
}
