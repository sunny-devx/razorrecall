package com.razorrecall.controller;

import com.razorrecall.domain.PaymentAttempt;
import com.razorrecall.domain.RecoveryCase;
import com.razorrecall.domain.RecoveryStatus;
import com.razorrecall.dto.ActionExecutionResult;
import com.razorrecall.dto.AiDiagnosisResult;
import com.razorrecall.service.RecoveryCaseService;
import com.razorrecall.service.RecoveryDecisionEngine;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/recovery/cases")
public class RecoveryCaseController {

    private final RecoveryCaseService recoveryCaseService;
    private final RecoveryDecisionEngine recoveryDecisionEngine;

    public record RecoveryCaseResponse(
            UUID id,
            UUID paymentAttemptId,
            String razorpayPaymentId,
            String orderId,
            Object amount,
            String currency,
            String status,
            String failureClass,
            boolean eligible,
            String failureReason,
            OffsetDateTime nextActionAt,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            String proposedStrategy,
            String aiDiagnosis,
            String aiSuggestedStrategy,
            Double aiConfidence,
            List<String> aiRationale,
            String aiProvider,
            Boolean aiFallbackUsed
    ) {
        public RecoveryCaseResponse(
                UUID id,
                UUID paymentAttemptId,
                String razorpayPaymentId,
                String orderId,
                Object amount,
                String currency,
                String status,
                String failureClass,
                boolean eligible,
                String failureReason,
                OffsetDateTime nextActionAt,
                OffsetDateTime createdAt,
                OffsetDateTime updatedAt
        ) {
            this(id, paymentAttemptId, razorpayPaymentId, orderId, amount, currency, status, failureClass, eligible, failureReason, nextActionAt, createdAt, updatedAt, null, null, null, null, null, null, null);
        }

        public static RecoveryCaseResponse from(RecoveryCase rc) {
            return from(rc, null);
        }

        public static RecoveryCaseResponse from(RecoveryCase rc, RecoveryDecisionEngine engine) {
            PaymentAttempt attempt = rc.getPaymentAttempt();
            RecoveryDecisionEngine.RecoveryDecision decision = null;
            if (engine != null && !RecoveryStatus.DETECTED.name().equalsIgnoreCase(rc.getStatus())) {
                try {
                    decision = engine.decide(rc);
                } catch (Exception ignored) {
                }
            }
            AiDiagnosisResult diag = decision != null ? decision.aiDiagnosis() : null;
            return new RecoveryCaseResponse(
                    rc.getId(),
                    attempt != null ? attempt.getId() : null,
                    attempt != null ? attempt.getRazorpayPaymentId() : null,
                    attempt != null ? attempt.getOrderId() : null,
                    attempt != null ? attempt.getAmount() : null,
                    attempt != null ? attempt.getCurrency() : null,
                    rc.getStatus(),
                    rc.getFailureClass(),
                    rc.isEligible(),
                    attempt != null ? attempt.getFailureReason() : null,
                    rc.getNextActionAt(),
                    rc.getCreatedAt(),
                    rc.getUpdatedAt(),
                    decision != null && decision.strategy() != null ? decision.strategy().name() : null,
                    diag != null ? diag.failureExplanation() : null,
                    diag != null && diag.suggestedStrategy() != null ? diag.suggestedStrategy().name() : null,
                    diag != null ? diag.confidence() : null,
                    diag != null ? diag.rationale() : null,
                    diag != null ? diag.providerName() : null,
                    diag != null ? diag.fallbackUsed() : null
            );
        }
    }

    public record EvaluationResponse(
            UUID id,
            String status,
            String proposedStrategy,
            boolean eligible,
            OffsetDateTime nextActionAt,
            String message,
            String aiDiagnosis,
            String aiSuggestedStrategy,
            Double aiConfidence,
            List<String> aiRationale,
            String aiProvider,
            Boolean aiFallbackUsed
    ) {
        public EvaluationResponse(
                UUID id,
                String status,
                String proposedStrategy,
                boolean eligible,
                OffsetDateTime nextActionAt,
                String message
        ) {
            this(id, status, proposedStrategy, eligible, nextActionAt, message, null, null, null, null, null, null);
        }

        public static EvaluationResponse from(RecoveryCaseService.EvaluationResult result) {
            AiDiagnosisResult diag = result.aiDiagnosis();
            return new EvaluationResponse(
                    result.recoveryCase().getId(),
                    result.newStatus().name(),
                    result.proposedStrategy().name(),
                    result.recoveryCase().isEligible(),
                    result.nextActionAt(),
                    result.message(),
                    diag != null ? diag.failureExplanation() : null,
                    diag != null && diag.suggestedStrategy() != null ? diag.suggestedStrategy().name() : null,
                    diag != null ? diag.confidence() : null,
                    diag != null ? diag.rationale() : null,
                    diag != null ? diag.providerName() : null,
                    diag != null ? diag.fallbackUsed() : null
            );
        }
    }

    @Autowired
    public RecoveryCaseController(
            RecoveryCaseService recoveryCaseService,
            @Autowired(required = false) RecoveryDecisionEngine recoveryDecisionEngine
    ) {
        this.recoveryCaseService = recoveryCaseService;
        this.recoveryDecisionEngine = recoveryDecisionEngine;
    }

    public RecoveryCaseController(RecoveryCaseService recoveryCaseService) {
        this(recoveryCaseService, null);
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getCase(@PathVariable("id") String idStr) {
        UUID id;
        try {
            id = UUID.fromString(idStr);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }

        RecoveryCase recoveryCase = recoveryCaseService.findById(id);
        if (recoveryCase == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(RecoveryCaseResponse.from(recoveryCase, recoveryDecisionEngine));
    }

    @PostMapping("/{id}/evaluate")
    public ResponseEntity<?> evaluateCase(@PathVariable("id") String idStr) {
        UUID id;
        try {
            id = UUID.fromString(idStr);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid UUID format"));
        }

        try {
            RecoveryCaseService.EvaluationResult result = recoveryCaseService.evaluateCase(id);
            return ResponseEntity.ok(EvaluationResponse.from(result));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "Evaluation failed"));
        }
    }

    @PostMapping("/evaluate-detected")
    public ResponseEntity<List<EvaluationResponse>> evaluateDetectedCases() {
        List<RecoveryCaseService.EvaluationResult> results = recoveryCaseService.evaluateDetectedCases();
        List<EvaluationResponse> responses = results.stream()
                .map(EvaluationResponse::from)
                .toList();
        return ResponseEntity.ok(responses);
    }

    @PostMapping("/{id}/dispatch")
    public ResponseEntity<?> dispatchAction(
            @PathVariable("id") String idStr,
            @RequestParam(value = "force", defaultValue = "false") boolean force
    ) {
        UUID id;
        try {
            id = UUID.fromString(idStr);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid UUID format"));
        }

        try {
            ActionExecutionResult result = recoveryCaseService.dispatchAction(id, force);
            if (result.targetStatus() == RecoveryStatus.ACTION_FAILED) {
                return ResponseEntity.status(502).body(Map.of(
                        "error", "Payment gateway dispatch failed",
                        "recoveryCaseId", result.recoveryCaseId(),
                        "status", result.targetStatus().name(),
                        "message", result.message()
                ));
            }
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage() != null ? e.getMessage() : "Dispatch failed"));
        }
    }

    @PostMapping("/dispatch-due")
    public ResponseEntity<List<ActionExecutionResult>> dispatchDueCases() {
        List<ActionExecutionResult> results = recoveryCaseService.dispatchDueCases();
        return ResponseEntity.ok(results);
    }

    @GetMapping
    public ResponseEntity<List<RecoveryCaseResponse>> listCases(
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "eligible", required = false) Boolean eligible
    ) {
        List<RecoveryCase> cases = recoveryCaseService.listCases(status, eligible);
        List<RecoveryCaseResponse> responses = cases.stream()
                .map(rc -> RecoveryCaseResponse.from(rc, recoveryDecisionEngine))
                .toList();

        return ResponseEntity.ok(responses);
    }

    @GetMapping("/metrics")
    public ResponseEntity<com.razorrecall.dto.RecoveryMetricsResponse> getMetrics() {
        return ResponseEntity.ok(recoveryCaseService.getMetrics());
    }
}
