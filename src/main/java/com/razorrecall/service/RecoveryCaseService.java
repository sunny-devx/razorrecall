package com.razorrecall.service;

import com.razorrecall.domain.RecoveryCase;
import com.razorrecall.repository.RecoveryCaseRepository;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class RecoveryCaseService {

    private final RecoveryCaseRepository recoveryCaseRepository;

    public RecoveryCaseService(RecoveryCaseRepository recoveryCaseRepository) {
        this.recoveryCaseRepository = recoveryCaseRepository;
    }

    public RecoveryCase save(RecoveryCase recoveryCase) {
        return recoveryCaseRepository.save(recoveryCase);
    }

    public RecoveryCase findById(UUID id) {
        return recoveryCaseRepository.findById(id)
                .orElse(null);
    }
}