package com.razorrecall.controller;

import com.razorrecall.domain.RecoveryCase;
import com.razorrecall.dto.ActionExecutionResult;
import com.razorrecall.service.RecoveryCaseService;
import com.razorrecall.service.RecoverySchedulerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/recovery/scheduler")
public class RecoverySchedulerController {

    private static final Logger log = LoggerFactory.getLogger(RecoverySchedulerController.class);

    private final ObjectProvider<RecoverySchedulerService> schedulerProvider;
    private final RecoveryCaseService recoveryCaseService;
    private final long expiryWindowHours;
    private final Clock clock;

    public record SchedulerCycleResponse(
            String status,
            int evaluated,
            int dispatched,
            int expired,
            String message
    ) {}

    @Autowired
    public RecoverySchedulerController(
            ObjectProvider<RecoverySchedulerService> schedulerProvider,
            RecoveryCaseService recoveryCaseService,
            @Value("${razorrecall.scheduler.expiry-window-hours:24}") long expiryWindowHours
    ) {
        this.schedulerProvider = schedulerProvider;
        this.recoveryCaseService = recoveryCaseService;
        this.expiryWindowHours = expiryWindowHours;
        this.clock = Clock.systemUTC();
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        boolean active = schedulerProvider.getIfAvailable() != null;
        return ResponseEntity.ok(Map.of(
                "enabled", active,
                "status", active ? "ACTIVE (Autonomous Background Loop)" : "STANDBY (Triggerable On-Demand)",
                "expiryWindowHours", expiryWindowHours
        ));
    }

    @PostMapping("/run")
    public ResponseEntity<SchedulerCycleResponse> runCycle() {
        RecoverySchedulerService scheduler = schedulerProvider.getIfAvailable();
        if (scheduler != null) {
            RecoverySchedulerService.AutonomousCycleSummary summary = scheduler.runAutonomousCycle();
            return ResponseEntity.ok(new SchedulerCycleResponse(
                    "COMPLETED",
                    summary.evaluated(),
                    summary.dispatched(),
                    summary.expired(),
                    "Autonomous recovery cycle executed via active scheduler service"
            ));
        }

        // On-demand execution when background scheduled timer is disabled
        try {
            List<RecoveryCaseService.EvaluationResult> evaluated = recoveryCaseService.evaluateDetectedCases();
            List<ActionExecutionResult> dispatched = recoveryCaseService.dispatchDueCases();
            OffsetDateTime cutoff = OffsetDateTime.now(clock).minusHours(expiryWindowHours);
            List<RecoveryCase> expired = recoveryCaseService.expireStaleCases(cutoff);

            int evalCount = evaluated != null ? evaluated.size() : 0;
            int dispCount = dispatched != null ? dispatched.size() : 0;
            int expCount = expired != null ? expired.size() : 0;

            log.info("[ON-DEMAND AUTONOMOUS CYCLE] evaluated={}, dispatched={}, expired={}",
                    evalCount, dispCount, expCount);

            return ResponseEntity.ok(new SchedulerCycleResponse(
                    "COMPLETED",
                    evalCount,
                    dispCount,
                    expCount,
                    "Autonomous recovery cycle executed on-demand (background timer is disabled)"
            ));
        } catch (Exception e) {
            log.error("[ON-DEMAND AUTONOMOUS CYCLE] Error: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(new SchedulerCycleResponse(
                    "FAILED",
                    0,
                    0,
                    0,
                    "Error executing autonomous cycle: " + e.getMessage()
            ));
        }
    }
}
