package com.razorrecall.service;

import com.razorrecall.dto.PaymentLinkRequest;
import com.razorrecall.dto.PaymentLinkResponse;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class MockRazorpayGatewayClient implements RazorpayGatewayClient {

    private volatile boolean simulateFailure = false;
    private final AtomicInteger invocationCount = new AtomicInteger(0);

    public void setSimulateFailure(boolean simulateFailure) {
        this.simulateFailure = simulateFailure;
    }

    public boolean isSimulateFailure() {
        return simulateFailure;
    }

    public int getInvocationCount() {
        return invocationCount.get();
    }

    public void resetInvocationCount() {
        invocationCount.set(0);
    }

    @Override
    public PaymentLinkResponse createPaymentLink(PaymentLinkRequest request) {
        invocationCount.incrementAndGet();

        if (simulateFailure) {
            throw new RuntimeException("Simulated Razorpay gateway connection timeout");
        }

        if (request == null) {
            throw new IllegalArgumentException("PaymentLinkRequest must not be null");
        }

        String ref = request.referenceId() != null ? request.referenceId() : String.valueOf(System.nanoTime());
        String hash = hashString(ref + ":" + request.amount());
        String linkId = "plink_" + hash.substring(0, 14);
        String shortUrl = "https://rzp.io/i/" + hash.substring(0, 8);
        long now = Instant.now().getEpochSecond();

        return new PaymentLinkResponse(
                linkId,
                shortUrl,
                "created",
                request.amount(),
                request.currency(),
                request.referenceId(),
                now
        );
    }

    @Override
    public boolean verifyPaymentStatus(String paymentId) {
        return paymentId != null && !paymentId.isBlank();
    }

    private String hashString(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] encoded = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(encoded);
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(input.hashCode());
        }
    }
}
