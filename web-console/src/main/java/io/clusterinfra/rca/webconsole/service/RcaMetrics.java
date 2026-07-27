package io.clusterinfra.rca.webconsole.service;

import io.clusterinfra.rca.webconsole.domain.RcaModels.AnalysisTaskStatus;
import io.clusterinfra.rca.webconsole.domain.RcaModels.NodeAgent;
import io.clusterinfra.rca.webconsole.domain.RcaModels.ReviewerCredentialState;
import io.clusterinfra.rca.webconsole.domain.RcaModels.ReviewerCredentialStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

@Component
public class RcaMetrics {
    private final MeterRegistry registry;
    private final AtomicLong agentOfflineCount = new AtomicLong();
    private final AtomicLong agentHeartbeatLagMaxSeconds = new AtomicLong();
    private final AtomicLong analysisQueueDepth = new AtomicLong();
    private final AtomicLong analysisDeadLetterCount = new AtomicLong();
    private final AtomicLong notificationQueueDepth = new AtomicLong();
    private final AtomicLong notificationDeadLetterCount = new AtomicLong();
    private final AtomicLong reviewerCredentialUnavailableCount = new AtomicLong();
    private final AtomicLong reviewerCredentialExpiringCount = new AtomicLong();
    private final AtomicLong reviewerCredentialRotatingCount = new AtomicLong();

    public RcaMetrics(MeterRegistry registry) {
        this.registry = registry;
        gauge(
            "rca.agent.offline.count",
            "Number of agents currently past the heartbeat freshness threshold",
            agentOfflineCount
        );
        gauge(
            "rca.agent.heartbeat.lag.max.seconds",
            "Maximum heartbeat lag across registered agents",
            agentHeartbeatLagMaxSeconds
        );
        gauge(
            "rca.analysis.queue.depth",
            "Number of queued, retry-waiting, or processing RCA analysis tasks",
            analysisQueueDepth
        );
        gauge(
            "rca.analysis.dead.letter.count",
            "Number of RCA analysis tasks currently in dead-letter state",
            analysisDeadLetterCount
        );
        gauge(
            "rca.notification.queue.depth",
            "Number of queued, retry-waiting, or processing notification outbox events",
            notificationQueueDepth
        );
        gauge(
            "rca.notification.dead.letter.count",
            "Number of notification outbox events currently in dead-letter state",
            notificationDeadLetterCount
        );
        gauge(
            "rca.agent.reviewer.credentials.unavailable.count",
            "Number of reviewer credentials that are missing, invalid, or expired",
            reviewerCredentialUnavailableCount
        );
        gauge(
            "rca.agent.reviewer.credentials.expiring.count",
            "Number of reviewer credentials close to expiry",
            reviewerCredentialExpiringCount
        );
        gauge(
            "rca.agent.reviewer.credentials.rotating.count",
            "Number of reviewer credentials currently retaining a previous credential",
            reviewerCredentialRotatingCount
        );
    }

    public void webhookIngest(String result, int payloads, int alerts) {
        increment("rca.webhook.ingest", "Alertmanager webhook payloads received", payloads, "result", result);
        increment("rca.webhook.alerts", "Alertmanager alerts received", alerts, "result", result);
    }

    public void evidenceRequest(String source, String result, int count) {
        increment(
            "rca.evidence.requests",
            "Evidence collection requests created by the platform",
            count,
            "source",
            source,
            "result",
            result
        );
    }

    public void evidenceCollection(String result, Duration duration) {
        increment(
            "rca.evidence.collection",
            "Evidence collection responses received from node agents",
            1,
            "result",
            result
        );
        timer(
            "rca.evidence.collection.duration",
            "Elapsed time from evidence request creation to agent response",
            "result",
            result
        ).record(nonNegative(duration));
    }

    public void agentHeartbeat(String status) {
        increment(
            "rca.agent.heartbeat",
            "Agent heartbeats accepted by the platform",
            1,
            "status",
            status
        );
    }

    public void analysisClaimed(int count) {
        increment("rca.analysis.task.claimed", "RCA analysis tasks claimed by workers", count);
    }

    public void analysisCompleted(AnalysisTaskStatus status, Duration duration) {
        increment(
            "rca.analysis.task.completed",
            "RCA analysis tasks completed or skipped",
            1,
            "status",
            status.name()
        );
        timer(
            "rca.analysis.task.duration",
            "End-to-end RCA analysis task duration",
            "status",
            status.name()
        ).record(nonNegative(duration));
    }

    public void analysisFailed(AnalysisTaskStatus status) {
        increment(
            "rca.analysis.task.failed",
            "RCA analysis task attempts that failed",
            1,
            "status",
            status.name()
        );
        if (status == AnalysisTaskStatus.dead_letter) {
            increment(
                "rca.analysis.task.dead.letter",
                "RCA analysis tasks moved to dead-letter state",
                1
            );
        }
    }

    public void reportGenerated(String result, Duration duration) {
        increment(
            "rca.report.generation",
            "RCA report generation attempts",
            1,
            "result",
            result
        );
        timer(
            "rca.report.generation.duration",
            "RCA report generation duration",
            "result",
            result
        ).record(nonNegative(duration));
    }

    public void incident(String result) {
        increment(
            "rca.incident",
            "RCA incident correlation outcomes",
            1,
            "result",
            result
        );
    }

    public void postCommitFailure(String operation) {
        increment(
            "rca.pipeline.post.commit.failure",
            "Best-effort RCA post-commit operations that failed after durable persistence",
            1,
            "operation",
            operation
        );
    }

    public void incidentLifecycle(String result, int count) {
        increment(
            "rca.incident.lifecycle",
            "Incident lifecycle transitions performed by the platform",
            count,
            "result",
            result
        );
    }

    public void llmAnalysis(String result, String provider, Duration duration) {
        increment(
            "rca.llm.analysis",
            "LLM analysis outcomes",
            1,
            "result",
            result,
            "provider",
            provider
        );
        timer(
            "rca.llm.analysis.duration",
            "LLM analysis duration including validation and retries",
            "result",
            result,
            "provider",
            provider
        ).record(nonNegative(duration));
    }

    public void llmRequest(
        String operation,
        String result,
        String provider,
        String model,
        Duration duration
    ) {
        increment(
            "rca.llm.request",
            "LLM provider request outcomes",
            1,
            "operation", operation,
            "result", result,
            "provider", provider,
            "model", model
        );
        timer(
            "rca.llm.request.duration",
            "LLM provider request duration",
            "operation", operation,
            "result", result,
            "provider", provider,
            "model", model
        ).record(nonNegative(duration));
    }

    public void llmUsage(
        String operation,
        String provider,
        String model,
        int inputTokens,
        int outputTokens,
        int totalTokens,
        double estimatedCostUsd,
        boolean usageAvailable
    ) {
        increment(
            "rca.llm.usage",
            "LLM provider usage metadata availability",
            1,
            "operation", operation,
            "provider", provider,
            "model", model,
            "result", usageAvailable ? "available" : "unavailable"
        );
        if (!usageAvailable) {
            return;
        }
        incrementDouble(
            "rca.llm.tokens",
            "LLM tokens reported by the provider",
            inputTokens,
            "operation", operation,
            "provider", provider,
            "model", model,
            "type", "input"
        );
        incrementDouble(
            "rca.llm.tokens",
            "LLM tokens reported by the provider",
            outputTokens,
            "operation", operation,
            "provider", provider,
            "model", model,
            "type", "output"
        );
        incrementDouble(
            "rca.llm.tokens",
            "LLM tokens reported by the provider",
            totalTokens,
            "operation", operation,
            "provider", provider,
            "model", model,
            "type", "total"
        );
        incrementDouble(
            "rca.llm.estimated.cost.usd",
            "Estimated LLM cost in USD using configured per-million-token prices",
            estimatedCostUsd,
            "operation", operation,
            "provider", provider,
            "model", model
        );
    }

    public void notification(String result, String severity) {
        increment(
            "rca.notification",
            "Incident notification delivery outcomes",
            1,
            "result",
            result,
            "severity",
            severity
        );
    }

    public void retentionCleanup(String dataType, int count) {
        incrementIncludingZero(
            "rca.maintenance.retention.deleted",
            "Records deleted by the configured retention policy",
            count,
            "data_type",
            dataType
        );
    }

    public void maintenanceRun(String result, Duration duration) {
        increment(
            "rca.maintenance.run",
            "Scheduled platform maintenance runs",
            1,
            "result",
            result
        );
        timer(
            "rca.maintenance.duration",
            "Scheduled platform maintenance duration",
            "result",
            result
        ).record(nonNegative(duration));
    }

    public void refreshOperationalGauges(
        List<NodeAgent> agents,
        long offlineAfterSeconds,
        long queueDepth,
        long deadLetterCount,
        long notificationQueueDepth,
        long notificationDeadLetterCount
    ) {
        Instant now = Instant.now();
        long offline = 0;
        long maximumLag = 0;
        for (NodeAgent agent : agents) {
            long lag = agent.lastHeartbeatAt() == null
                ? Math.max(offlineAfterSeconds + 1, 1)
                : Math.max(0, Duration.between(agent.lastHeartbeatAt(), now).getSeconds());
            maximumLag = Math.max(maximumLag, lag);
            if (agent.lastHeartbeatAt() == null || lag > offlineAfterSeconds) {
                offline++;
            }
        }
        agentOfflineCount.set(offline);
        agentHeartbeatLagMaxSeconds.set(maximumLag);
        analysisQueueDepth.set(Math.max(0, queueDepth));
        analysisDeadLetterCount.set(Math.max(0, deadLetterCount));
        this.notificationQueueDepth.set(Math.max(0, notificationQueueDepth));
        this.notificationDeadLetterCount.set(Math.max(0, notificationDeadLetterCount));
    }

    public void refreshReviewerCredentialGauges(List<ReviewerCredentialStatus> statuses) {
        long unavailable = statuses.stream()
            .filter(status -> status.state() == ReviewerCredentialState.missing
                || status.state() == ReviewerCredentialState.invalid
                || status.state() == ReviewerCredentialState.expired)
            .count();
        long expiring = statuses.stream()
            .filter(status -> status.state() == ReviewerCredentialState.expiring)
            .count();
        long rotating = statuses.stream()
            .filter(status -> status.state() == ReviewerCredentialState.rotating)
            .count();
        reviewerCredentialUnavailableCount.set(unavailable);
        reviewerCredentialExpiringCount.set(expiring);
        reviewerCredentialRotatingCount.set(rotating);
    }

    private void increment(String name, String description, int amount, String... tags) {
        if (amount <= 0) {
            return;
        }
        incrementIncludingZero(name, description, amount, tags);
    }

    private void incrementIncludingZero(String name, String description, int amount, String... tags) {
        Counter.builder(name)
            .description(description)
            .tags(normalizedTags(tags))
            .register(registry)
            .increment(Math.max(0, amount));
    }

    private void incrementDouble(String name, String description, double amount, String... tags) {
        if (!Double.isFinite(amount) || amount < 0) {
            return;
        }
        Counter.builder(name)
            .description(description)
            .tags(normalizedTags(tags))
            .register(registry)
            .increment(amount);
    }

    private Timer timer(String name, String description, String... tags) {
        return Timer.builder(name)
            .description(description)
            .tags(normalizedTags(tags))
            .register(registry);
    }

    private void gauge(String name, String description, AtomicLong value) {
        Gauge.builder(name, value, AtomicLong::doubleValue)
            .description(description)
            .register(registry);
    }

    private String[] normalizedTags(String[] tags) {
        String[] normalized = tags.clone();
        for (int index = 1; index < normalized.length; index += 2) {
            normalized[index] = normalized(normalized[index]);
        }
        return normalized;
    }

    private String normalized(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]+", "_");
        return normalized.length() <= 80 ? normalized : normalized.substring(0, 80);
    }

    private Duration nonNegative(Duration duration) {
        return duration == null || duration.isNegative() ? Duration.ZERO : duration;
    }
}
