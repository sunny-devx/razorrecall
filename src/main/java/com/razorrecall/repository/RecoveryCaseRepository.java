package com.razorrecall.repository;

import com.razorrecall.domain.RecoveryCase;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface RecoveryCaseRepository
        extends JpaRepository<RecoveryCase, UUID> {

    Optional<RecoveryCase> findByPaymentAttemptId(UUID paymentAttemptId);
}