package com.razorrecall.dto;

public record PaymentLinkResponse(
        String id,
        String shortUrl,
        String status,
        long amount,
        String currency,
        String referenceId,
        long createdAt
) {}
