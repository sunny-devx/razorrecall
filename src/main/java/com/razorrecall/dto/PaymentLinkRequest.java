package com.razorrecall.dto;

public record PaymentLinkRequest(
        long amount,
        String currency,
        String description,
        String referenceId,
        String customerPhone,
        String customerEmail
) {
    public PaymentLinkRequest {
        if (amount <= 0) {
            throw new IllegalArgumentException("Amount must be greater than zero");
        }
        if (currency == null || currency.isBlank()) {
            throw new IllegalArgumentException("Currency must not be blank");
        }
    }
}
