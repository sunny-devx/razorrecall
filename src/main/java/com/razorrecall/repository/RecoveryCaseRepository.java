package com.razorrecall.repository;

import com.razorrecall.domain.RecoveryCase;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface RecoveryCaseRepository
        extends JpaRepository<RecoveryCase, UUID> {
}