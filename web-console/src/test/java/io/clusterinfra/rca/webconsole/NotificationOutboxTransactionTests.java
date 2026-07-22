package io.clusterinfra.rca.webconsole;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.clusterinfra.rca.webconsole.domain.RcaModels.ClusterCreateRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.Confidence;
import io.clusterinfra.rca.webconsole.domain.RcaModels.EvidenceBundle;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RcaJob;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RcaJobStatus;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RcaReport;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RcaSummary;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RootCauseCandidate;
import io.clusterinfra.rca.webconsole.persistence.ClusterRepository;
import io.clusterinfra.rca.webconsole.service.IncidentPersistenceService;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:notification-transaction-${random.uuid};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.ai.model.chat=none",
    "rca.pipeline.enabled=false",
    "rca.monitoring.enabled=false",
    "rca.observability.enabled=false",
    "rca.notification.enabled=true",
    "rca.notification.minimum-severity=warning",
    "rca.notification.webhook-url=http://127.0.0.1:1/webhook",
    "rca.notification.initial-delay-ms=600000"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class NotificationOutboxTransactionTests {
    private static final Instant NOW = Instant.parse("2026-07-22T01:00:00Z");

    @Autowired
    private ClusterRepository clusters;

    @Autowired
    private IncidentPersistenceService persistence;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void outboxInsertFailureRollsBackIncidentReportAndJobTogether() {
        var cluster = clusters.create(new ClusterCreateRequest("transaction-test", "test", null));
        EvidenceBundle evidence = evidence(cluster.clusterId());
        jdbc.execute("DROP TABLE notification_outbox");

        assertThatThrownBy(() -> persistence.saveCorrelated(
            report(cluster.clusterId()),
            job(cluster.clusterId()),
            "transaction-test-dedup",
            null,
            false,
            null,
            0,
            evidence
        )).isInstanceOf(RuntimeException.class);

        assertThat(count("incidents")).isZero();
        assertThat(count("rca_reports")).isZero();
        assertThat(count("rca_jobs")).isZero();
    }

    private EvidenceBundle evidence(String clusterId) {
        jdbc.update(
            """
                INSERT INTO evidence_bundles
                    (evidence_id, cluster_id, node_name, alert_name, collectors_json, collected_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
            "evidence-transaction",
            clusterId,
            "worker-a",
            "DiskPressure",
            "{\"disk\":{\"usage_percent\":96}}",
            Timestamp.from(NOW)
        );
        return new EvidenceBundle(
            "evidence-transaction",
            clusterId,
            "worker-a",
            "DiskPressure",
            NOW,
            Map.of("disk", Map.of("usage_percent", 96))
        );
    }

    private RcaReport report(String clusterId) {
        return new RcaReport(
            "report-transaction",
            clusterId,
            null,
            RcaJobStatus.completed,
            Map.of("alert_name", "DiskPressure"),
            Map.of("node_name", "worker-a"),
            new RcaSummary("DiskPressure", "Disk is full", Confidence.high),
            List.of(Map.of(
                "type", "derived_signals",
                "signals", List.of(Map.of("severity", "critical"))
            )),
            List.of(new RootCauseCandidate(
                "Disk is full",
                Confidence.high,
                List.of("disk usage is 96 percent")
            )),
            List.of(),
            List.of(),
            NOW
        );
    }

    private RcaJob job(String clusterId) {
        return new RcaJob(
            "job-transaction",
            clusterId,
            "DiskPressure",
            "worker-a",
            RcaJobStatus.completed,
            "report-transaction",
            "evidence-transaction",
            NOW
        );
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }
}
