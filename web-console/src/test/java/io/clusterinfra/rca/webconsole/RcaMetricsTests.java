package io.clusterinfra.rca.webconsole;

import static org.assertj.core.api.Assertions.assertThat;

import io.clusterinfra.rca.webconsole.domain.RcaModels.AgentStatus;
import io.clusterinfra.rca.webconsole.domain.RcaModels.AnalysisTaskStatus;
import io.clusterinfra.rca.webconsole.domain.RcaModels.NodeAgent;
import io.clusterinfra.rca.webconsole.service.RcaMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RcaMetricsTests {
    @Test
    void recordsBoundedDomainMetricsAndOperationalGauges() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RcaMetrics metrics = new RcaMetrics(registry);

        metrics.webhookIngest("accepted", 1, 3);
        metrics.evidenceRequest("alertmanager", "created", 2);
        metrics.evidenceCollection("completed", Duration.ofSeconds(4));
        metrics.analysisClaimed(2);
        metrics.analysisCompleted(AnalysisTaskStatus.completed, Duration.ofSeconds(2));
        metrics.analysisFailed(AnalysisTaskStatus.dead_letter);
        metrics.reportGenerated("created", Duration.ofMillis(250));
        metrics.incident("created");
        metrics.llmAnalysis("completed", "openai", Duration.ofMillis(500));
        metrics.llmRequest("analysis", "completed", "openai", "gpt-test", Duration.ofMillis(450));
        metrics.llmUsage("analysis", "openai", "gpt-test", 100, 25, 125, 0.00125, true);
        metrics.notification("sent", "critical");
        metrics.retentionCleanup("evidence_bundles", 3);
        metrics.maintenanceRun("completed", Duration.ofSeconds(1));
        metrics.refreshOperationalGauges(
            List.of(
                agent("fresh", Instant.now().minusSeconds(10)),
                agent("stale", Instant.now().minusSeconds(300)),
                agent("missing", null)
            ),
            180,
            7,
            2
        );

        assertThat(registry.get("rca.webhook.alerts").tag("result", "accepted").counter().count())
            .isEqualTo(3);
        assertThat(registry.get("rca.evidence.requests")
            .tag("source", "alertmanager").tag("result", "created").counter().count())
            .isEqualTo(2);
        assertThat(registry.get("rca.report.generation.duration")
            .tag("result", "created").timer().count()).isEqualTo(1);
        assertThat(registry.get("rca.analysis.task.dead.letter").counter().count()).isEqualTo(1);
        assertThat(registry.get("rca.maintenance.retention.deleted")
            .tag("data_type", "evidence_bundles").counter().count()).isEqualTo(3);
        assertThat(registry.get("rca.maintenance.duration")
            .tag("result", "completed").timer().count()).isEqualTo(1);
        assertThat(registry.get("rca.llm.request.duration")
            .tag("operation", "analysis").tag("result", "completed")
            .tag("provider", "openai").tag("model", "gpt-test").timer().count()).isEqualTo(1);
        assertThat(registry.get("rca.llm.tokens")
            .tag("operation", "analysis").tag("provider", "openai")
            .tag("model", "gpt-test").tag("type", "total").counter().count()).isEqualTo(125);
        assertThat(registry.get("rca.agent.offline.count").gauge().value()).isEqualTo(2);
        assertThat(registry.get("rca.analysis.queue.depth").gauge().value()).isEqualTo(7);
        assertThat(registry.get("rca.analysis.dead.letter.count").gauge().value()).isEqualTo(2);
        assertThat(registry.get("rca.agent.heartbeat.lag.max.seconds").gauge().value())
            .isGreaterThanOrEqualTo(300);
    }

    private NodeAgent agent(String nodeName, Instant heartbeat) {
        return new NodeAgent(
            "agent-" + nodeName,
            "cluster-1",
            nodeName,
            "0.1.0",
            "1",
            AgentStatus.healthy,
            List.of("node"),
            Map.of(),
            Map.of(),
            Instant.now().minusSeconds(600),
            heartbeat
        );
    }
}
