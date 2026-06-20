package io.clusterinfra.rca.webconsole;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.clusterinfra.rca.webconsole.config.RcaConsoleProperties;
import io.clusterinfra.rca.webconsole.domain.RcaModels.Confidence;
import io.clusterinfra.rca.webconsole.domain.RcaModels.EvidenceBundle;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RcaJobStatus;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RcaReport;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RcaSummary;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RootCauseCandidate;
import io.clusterinfra.rca.webconsole.service.AuditService;
import io.clusterinfra.rca.webconsole.service.IncidentNotificationService;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class IncidentNotificationServiceTests {
    @Test
    void sendsRedactedSlackPayloadWithoutChangingReportFlow() throws Exception {
        AtomicReference<String> received = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/slack", exchange -> {
            received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, 0);
            exchange.close();
        });
        server.start();
        try {
            RcaConsoleProperties properties = new RcaConsoleProperties();
            properties.getNotification().setEnabled(true);
            properties.getNotification().setMinimumSeverity("warning");
            properties.getNotification().setSlackWebhookUrl(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/slack"
            );
            AuditService audit = mock(AuditService.class);
            IncidentNotificationService service = new IncidentNotificationService(
                properties,
                audit,
                new ObjectMapper()
            );
            RcaReport report = report("Disk full token=private-value");

            service.notifyIncident(report, evidence());

            assertThat(received.get())
                .contains("[Cluster RCA Alert]")
                .contains("Confidence: 90%")
                .contains("token=[redacted]")
                .doesNotContain("private-value");
            verify(audit).system(
                eq("notification"),
                eq("notification.sent"),
                eq("incident"),
                eq("incident-1"),
                eq("success"),
                any()
            );
        } finally {
            server.stop(0);
        }
    }

    private RcaReport report(String cause) {
        return new RcaReport(
            "report-1",
            "cluster-1",
            "incident-1",
            RcaJobStatus.completed,
            Map.of("alert_name", "DiskPressure"),
            Map.of("nodes", List.of("worker-a")),
            new RcaSummary("DiskPressure", cause, Confidence.high),
            List.of(Map.of(
                "type", "derived_signals",
                "signals", List.of(Map.of("severity", "critical"))
            )),
            List.of(new RootCauseCandidate(cause, Confidence.high, List.of("disk=96%"), 90, List.of("disk.usage"))),
            List.of(),
            List.of(),
            Instant.now()
        );
    }

    private EvidenceBundle evidence() {
        return new EvidenceBundle(
            "evidence-1",
            "cluster-1",
            "worker-a",
            "DiskPressure",
            Instant.now(),
            Map.of()
        );
    }
}
