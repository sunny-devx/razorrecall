package com.razorrecall.service;

import com.razorrecall.domain.RecoveryCase;
import com.razorrecall.dto.ActionExecutionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;

@Component
@ConditionalOnProperty(prefix = "razorrecall.scheduler", name = "enabled", havingValue = "true", matchIfMissing = false)
public class RecoverySchedulerService {

    private static final Logger log = LoggerFactory.getLogger(RecoverySchedulerService.class);

    private final RecoveryCaseService recoveryCaseService;
    private final long expiryWindowHours;
    private final Clock clock;

    public record AutonomousCycleSummary(int evaluated, int dispatched, int expired) {}

    @Autowired
    public RecoverySchedulerService(
            RecoveryCaseService recoveryCaseService,
            @Value("${razorrecall.scheduler.expiry-window-hours:24}") long expiryWindowHours
    ) {
        this(recoveryCaseService, expiryWindowHours, Clock.systemUTC());
    }

    public RecoverySchedulerService(
            RecoveryCaseService recoveryCaseService,
            long expiryWindowHours,
            Clock clock
    ) {
        this.recoveryCaseService = recoveryCaseService;
        this.expiryWindowHours = expiryWindowHours;
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    @Scheduled(fixedDelayString = "${razorrecall.scheduler.eval-interval-ms:10000}")
    public int scheduleEvaluation() {
        try {
            List<RecoveryCaseService.EvaluationResult> evaluated = recoveryCaseService.evaluateDetectedCases();
            int count = evaluated.size();
            log.info("[AUTONOMOUS SCHEDULER] evaluated={}", count);
            return count;
        } catch (Exception e) {
            log.error("[AUTONOMOUS SCHEDULER] Evaluation error: {}", e.getMessage(), e);
            return 0;
        }
    }

    @Scheduled(fixedDelayString = "${razorrecall.scheduler.dispatch-interval-ms:15000}")
    public int scheduleDispatch() {
        try {
            List<ActionExecutionResult> dispatched = recoveryCaseService.dispatchDueCases();
            int count = dispatched.size();
            log.info("[AUTONOMOUS SCHEDULER] dispatched={}", count);
            return count;
        } catch (Exception e) {
            log.error("[AUTONOMOUS SCHEDULER] Dispatch error: {}", e.getMessage(), e);
            return 0;
        }
    }

    @Scheduled(fixedDelayString = "${razorrecall.scheduler.expire-interval-ms:60000}")
    public int scheduleExpiration() {
        try {
            OffsetDateTime cutoff = OffsetDateTime.now(clock).minusHours(expiryWindowHours);
            List<RecoveryCase> expired = recoveryCaseService.expireStaleCases(cutoff);
            int count = expired.size();
            log.info("[AUTONOMOUS SCHEDULER] expired={}", count);
            return count;
        } catch (Exception e) {
            log.error("[AUTONOMOUS SCHEDULER] Expiration error: {}", e.getMessage(), e);
            return 0;
        }
    }

    public AutonomousCycleSummary runAutonomousCycle() {
        int evaluated = scheduleEvaluation();
        int dispatched = scheduleDispatch();
        int expired = scheduleExpiration();

        log.info("[AUTONOMOUS SCHEDULER]\nevaluated={}\ndispatched={}\nexpired={}", evaluated, dispatched, expired);
        return new AutonomousCycleSummary(evaluated, dispatched, expired);
    }
}
