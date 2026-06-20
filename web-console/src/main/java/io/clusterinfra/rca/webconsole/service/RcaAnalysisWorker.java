package io.clusterinfra.rca.webconsole.service;

import io.clusterinfra.rca.webconsole.config.RcaConsoleProperties;
import io.clusterinfra.rca.webconsole.domain.RcaModels.AnalysisTask;
import io.clusterinfra.rca.webconsole.domain.RcaModels.AnalysisTaskStatus;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RcaJob;
import io.clusterinfra.rca.webconsole.persistence.AnalysisTaskRepository;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class RcaAnalysisWorker {
    private static final Logger LOGGER = LoggerFactory.getLogger(RcaAnalysisWorker.class);

    private final AnalysisTaskRepository tasks;
    private final RcaService rcaService;
    private final RcaConsoleProperties properties;
    private final AuditService audit;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final String workerId = "worker-" + UUID.randomUUID().toString().substring(0, 8);

    public RcaAnalysisWorker(
        AnalysisTaskRepository tasks,
        RcaService rcaService,
        RcaConsoleProperties properties,
        AuditService audit
    ) {
        this.tasks = tasks;
        this.rcaService = rcaService;
        this.properties = properties;
        this.audit = audit;
    }

    @Scheduled(
        fixedDelayString = "${rca.pipeline.poll-interval-ms:2000}",
        initialDelayString = "${rca.pipeline.initial-delay-ms:3000}"
    )
    public int processAvailableTasks() {
        if (!properties.getPipeline().isEnabled()) {
            return 0;
        }
        Instant now = Instant.now();
        List<AnalysisTask> claimedTasks = tasks.claim(
            workerId,
            properties.getPipeline().getBatchSize(),
            now,
            now.plusSeconds(Math.max(30, properties.getPipeline().getLeaseSeconds()))
        );
        List<Future<?>> futures = new ArrayList<>();
        for (AnalysisTask task : claimedTasks) {
            futures.add(executor.submit(() -> process(task)));
        }
        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (Exception exception) {
                LOGGER.error("RCA analysis worker thread failed", exception);
            }
        }
        return claimedTasks.size();
    }

    private void process(AnalysisTask task) {
        try {
            RcaJob job = rcaService.processAnalysisTask(task);
            Instant completedAt = Instant.now();
            AnalysisTaskStatus status = job == null
                ? AnalysisTaskStatus.skipped
                : AnalysisTaskStatus.completed;
            boolean updated = tasks.complete(
                task.taskId(),
                workerId,
                status,
                job == null ? null : job.reportId(),
                job == null ? null : job.jobId(),
                completedAt
            );
            if (!updated) {
                LOGGER.warn("Analysis task lease was lost before completion: {}", task.taskId());
                return;
            }
            recordAudit(
                task,
                "analysis.task_completed",
                status,
                Map.of(
                    "evidence_id", task.evidenceId(),
                    "attempt", task.attemptCount(),
                    "report_id", job == null ? "" : job.reportId()
                )
            );
        } catch (Exception exception) {
            fail(task, exception);
        }
    }

    private void fail(AnalysisTask task, Exception exception) {
        int retrySeconds = retryDelaySeconds(task.attemptCount());
        String error = safeError(exception);
        boolean updated = tasks.fail(
            task,
            workerId,
            error,
            Instant.now().plusSeconds(retrySeconds)
        );
        if (!updated) {
            LOGGER.warn("Analysis task lease was lost before failure handling: {}", task.taskId());
            return;
        }
        AnalysisTaskStatus status = task.attemptCount() >= task.maxAttempts()
            ? AnalysisTaskStatus.dead_letter
            : AnalysisTaskStatus.retry_wait;
        recordAudit(
            task,
            "analysis.task_failed",
            status,
            Map.of(
                "evidence_id", task.evidenceId(),
                "attempt", task.attemptCount(),
                "max_attempts", task.maxAttempts(),
                "retry_in_seconds", status == AnalysisTaskStatus.dead_letter ? 0 : retrySeconds,
                "error", error
            )
        );
        LOGGER.warn(
            "RCA analysis task failed task={} attempt={}/{} status={}: {}",
            task.taskId(),
            task.attemptCount(),
            task.maxAttempts(),
            status,
            error
        );
    }

    private void recordAudit(
        AnalysisTask task,
        String eventType,
        AnalysisTaskStatus status,
        Map<String, Object> details
    ) {
        try {
            audit.system(
                workerId,
                eventType,
                "analysis_task",
                task.taskId(),
                status.name(),
                details
            );
        } catch (RuntimeException exception) {
            LOGGER.warn("Failed to record analysis task audit event: {}", task.taskId(), exception);
        }
    }

    private int retryDelaySeconds(int attemptCount) {
        int base = Math.max(1, properties.getPipeline().getRetryBaseSeconds());
        int maximum = Math.max(base, properties.getPipeline().getRetryMaxSeconds());
        long multiplier = 1L << Math.min(Math.max(0, attemptCount - 1), 20);
        return (int) Math.min(maximum, base * multiplier);
    }

    private String safeError(Exception exception) {
        String message = exception.getMessage();
        String value = exception.getClass().getSimpleName()
            + (message == null || message.isBlank() ? "" : ": " + message);
        String redacted = value
            .replaceAll("(?i)(api[_-]?key|authorization|token)(\\s*[:=]\\s*)[^\\s,;]+", "$1$2[redacted]")
            .replaceAll("(?i)bearer\\s+[a-z0-9._-]+", "Bearer [redacted]")
            .replaceAll("sk-[a-zA-Z0-9_-]{8,}", "sk-[redacted]");
        return redacted.length() <= 2000 ? redacted : redacted.substring(0, 2000);
    }

    @PreDestroy
    public void shutdown() {
        executor.close();
    }
}
