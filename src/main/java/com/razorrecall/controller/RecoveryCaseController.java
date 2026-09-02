package com.razorrecall.controller;

import com.razorrecall.domain.PaymentAttempt;
import com.razorrecall.domain.RecoveryCase;
import com.razorrecall.domain.RecoveryStatus;
import com.razorrecall.dto.ActionExecutionResult;
import com.razorrecall.service.RecoveryCaseService;
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
            OffsetDateTime updatedAt
    ) {
        public static RecoveryCaseResponse from(RecoveryCase rc) {
            PaymentAttempt attempt = rc.getPaymentAttempt();
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
                    rc.getUpdatedAt()
            );
        }
    }

    public record EvaluationResponse(
            UUID id,
            String status,
            String proposedStrategy,
            boolean eligible,
            OffsetDateTime nextActionAt,
            String message
    ) {}

    public RecoveryCaseController(RecoveryCaseService recoveryCaseService) {
        this.recoveryCaseService = recoveryCaseService;
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
        return ResponseEntity.ok(RecoveryCaseResponse.from(recoveryCase));
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
            EvaluationResponse response = new EvaluationResponse(
                    result.recoveryCase().getId(),
                    result.newStatus().name(),
                    result.proposedStrategy().name(),
                    result.recoveryCase().isEligible(),
                    result.nextActionAt(),
                    result.message()
            );
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "Evaluation failed"));
        }
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
                .map(RecoveryCaseResponse::from)
                .toList();

        return ResponseEntity.ok(responses);
    }
}
