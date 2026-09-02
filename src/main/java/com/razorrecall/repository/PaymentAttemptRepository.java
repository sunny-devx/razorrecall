package com.razorrecall.repository;

import com.razorrecall.domain.PaymentAttempt;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentAttemptRepository extends JpaRepository<PaymentAttempt, UUID> {

    Optional<PaymentAttempt> findByRazorpayPaymentId(String razorpayPaymentId);

    List<PaymentAttempt> findByOrderIdOrderByCreatedAtDesc(String orderId);
}