package com.razorrecall.service;

import com.razorrecall.domain.PaymentAttempt;
import com.razorrecall.repository.PaymentAttemptRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

@Service
public class PaymentAttemptService {

    private final PaymentAttemptRepository paymentAttemptRepository;

    public PaymentAttemptService(PaymentAttemptRepository paymentAttemptRepository) {
        this.paymentAttemptRepository = paymentAttemptRepository;
    }

    public PaymentAttempt save(PaymentAttempt paymentAttempt) {
        return paymentAttemptRepository.save(paymentAttempt);
    }

    public PaymentAttempt findById(UUID id) {
        return paymentAttemptRepository.findById(id)
                .orElse(null);
    }

    public Optional<PaymentAttempt> findByRazorpayPaymentId(String razorpayPaymentId) {
        if (razorpayPaymentId == null || razorpayPaymentId.isBlank()) {
            return Optional.empty();
        }
        return paymentAttemptRepository.findByRazorpayPaymentId(razorpayPaymentId);
    }

    @Transactional
    public PaymentAttempt recordFailedPayment(
            UUID merchantId,
            String razorpayPaymentId,
            String orderId,
            BigDecimal amount,
            String currency,
            String failureReason
    ) {
        if (razorpayPaymentId != null && !razorpayPaymentId.isBlank()) {
            Optional<PaymentAttempt> existing = paymentAttemptRepository.findByRazorpayPaymentId(razorpayPaymentId);
            if (existing.isPresent()) {
                PaymentAttempt attempt = existing.get();
                attempt.setStatus("FAILED");
                if (failureReason != null) {
                    attempt.setFailureReason(failureReason);
                }
                return paymentAttemptRepository.save(attempt);
            }
        }

        PaymentAttempt attempt = new PaymentAttempt();
        attempt.setId(UUID.randomUUID());
        attempt.setMerchantId(merchantId);
        attempt.setRazorpayPaymentId(razorpayPaymentId);
        attempt.setOrderId(orderId);
        attempt.setAmount(amount != null ? amount : BigDecimal.ZERO);
        attempt.setCurrency(currency != null ? currency : "INR");
        attempt.setStatus("FAILED");
        attempt.setFailureReason(failureReason);
        attempt.setCreatedAt(OffsetDateTime.now());

        return paymentAttemptRepository.save(attempt);
    }
}