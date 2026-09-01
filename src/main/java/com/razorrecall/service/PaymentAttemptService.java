package com.razorrecall.service;

import com.razorrecall.domain.PaymentAttempt;
import com.razorrecall.repository.PaymentAttemptRepository;
import org.springframework.stereotype.Service;

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
}