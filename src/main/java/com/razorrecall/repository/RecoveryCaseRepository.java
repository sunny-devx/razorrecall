package com.razorrecall.repository;

import com.razorrecall.domain.RecoveryCase;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RecoveryCaseRepository
        extends JpaRepository<RecoveryCase, UUID> {

    Optional<RecoveryCase> findByPaymentAttemptId(UUID paymentAttemptId);

    List<RecoveryCase> findByStatusAndCreatedAtBefore(String status, OffsetDateTime cutoff);
}
