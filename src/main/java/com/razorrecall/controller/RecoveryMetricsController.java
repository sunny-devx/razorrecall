package com.razorrecall.controller;

import com.razorrecall.dto.RecoveryMetricsResponse;
import com.razorrecall.service.RecoveryCaseService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/recovery/metrics")
public class RecoveryMetricsController {

    private final RecoveryCaseService recoveryCaseService;

    public RecoveryMetricsController(RecoveryCaseService recoveryCaseService) {
        this.recoveryCaseService = recoveryCaseService;
    }

    @GetMapping
    public ResponseEntity<RecoveryMetricsResponse> getMetrics() {
        RecoveryMetricsResponse metrics = recoveryCaseService.getMetrics();
        return ResponseEntity.ok(metrics);
    }
}
