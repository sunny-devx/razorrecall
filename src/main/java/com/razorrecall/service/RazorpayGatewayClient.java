package com.razorrecall.service;

import com.razorrecall.dto.PaymentLinkRequest;
import com.razorrecall.dto.PaymentLinkResponse;

public interface RazorpayGatewayClient {

    PaymentLinkResponse createPaymentLink(PaymentLinkRequest request);

    boolean verifyPaymentStatus(String paymentId);
}
