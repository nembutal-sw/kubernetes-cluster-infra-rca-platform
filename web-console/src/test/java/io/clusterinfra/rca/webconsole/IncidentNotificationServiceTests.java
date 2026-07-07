package io.clusterinfra.rca.webconsole;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.clusterinfra.rca.webconsole.config.RcaConsoleProperties;
import io.clusterinfra.rca.webconsole.domain.RcaModels.Confidence;
import io.clusterinfra.rca.webconsole.domain.RcaModels.EvidenceBundle;
import io.clusterinfra.rca.webconsole.domain.RcaModels.NotificationTestResponse;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RcaJobStatus;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RcaReport;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RcaSummary;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RootCauseCandidate;
import io.clusterinfra.rca.webconsole.service.AuditService;
import io.clusterinfra.rca.webconsole.service.IncidentNotificationService;
import io.clusterinfra.rca.webconsole.service.RcaMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
                new ObjectMapper(),
                new RcaMetrics(new SimpleMeterRegistry())
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

    @Test
    void sendsNotificationWhenRootCauseCandidatesAreEmpty() throws Exception {
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
            IncidentNotificationService service = new IncidentNotificationService(
                properties,
                mock(AuditService.class),
                new ObjectMapper(),
                new RcaMetrics(new SimpleMeterRegistry())
            );
            RcaReport base = report("Cause is still under investigation");
            RcaReport withoutCandidates = new RcaReport(
                base.reportId(),
                base.clusterId(),
                base.incidentId(),
                base.status(),
                base.trigger(),
                base.scope(),
                base.summary(),
                base.evidence(),
                List.of(),
                base.recommendedActions(),
                base.policyDecisions(),
                base.createdAt()
            );

            service.notifyIncident(withoutCandidates, evidence());

            assertThat(received.get()).contains("Confidence: unavailable");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void sendsStructuredGenericWebhookPayloadWithBearerToken() throws Exception {
        AtomicReference<String> received = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/webhook", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(202, 0);
            exchange.close();
        });
        server.start();
        try {
            RcaConsoleProperties properties = new RcaConsoleProperties();
            properties.setPublicApiBaseUrl("https://rca.example.com/");
            properties.getNotification().setEnabled(true);
            properties.getNotification().setMinimumSeverity("warning");
            properties.getNotification().setWebhookUrl(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/webhook"
            );
            properties.getNotification().setWebhookToken("test-delivery-token");
            AuditService audit = mock(AuditService.class);
            IncidentNotificationService service = new IncidentNotificationService(
                properties,
                audit,
                new ObjectMapper(),
                new RcaMetrics(new SimpleMeterRegistry())
            );

            service.notifyIncident(report("Disk full token=private-value"), evidence());

            JsonNode payload = new ObjectMapper().readTree(received.get());
            assertThat(authorization.get()).isEqualTo("Bearer test-delivery-token");
            assertThat(payload.path("schema_version").asText()).isEqualTo("rca-notification/v1");
            assertThat(payload.path("event_type").asText()).isEqualTo("rca.incident");
            assertThat(payload.path("severity").asText()).isEqualTo("critical");
            assertThat(payload.path("cluster_id").asText()).isEqualTo("cluster-1");
            assertThat(payload.path("report_id").asText()).isEqualTo("report-1");
            assertThat(payload.path("node_name").asText()).isEqualTo("worker-a");
            assertThat(payload.path("confidence_score").asInt()).isEqualTo(90);
            assertThat(payload.path("report_url").asText()).isEqualTo("https://rca.example.com/?report=report-1");
            assertThat(payload.path("most_likely_cause").asText())
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

    @Test
    void testDeliveryUsesConfiguredGenericWebhookTarget() throws Exception {
        AtomicReference<String> received = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/webhook", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        try {
            RcaConsoleProperties properties = new RcaConsoleProperties();
            properties.setPublicApiBaseUrl("https://rca.example.com/");
            properties.getNotification().setEnabled(true);
            properties.getNotification().setWebhookUrl(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/webhook"
            );
            properties.getNotification().setWebhookToken("test-delivery-token");
            IncidentNotificationService service = new IncidentNotificationService(
                properties,
                mock(AuditService.class),
                new ObjectMapper(),
                new RcaMetrics(new SimpleMeterRegistry())
            );

            NotificationTestResponse response = service.testDelivery();

            JsonNode payload = new ObjectMapper().readTree(received.get());
            assertThat(response.outcome()).isEqualTo("success");
            assertThat(response.results()).hasSize(1);
            assertThat(response.results().getFirst().channel()).isEqualTo("webhook");
            assertThat(response.results().getFirst().statusCode()).isEqualTo(204);
            assertThat(authorization.get()).isEqualTo("Bearer test-delivery-token");
            assertThat(payload.path("schema_version").asText()).isEqualTo("rca-notification/v1");
            assertThat(payload.path("event_type").asText()).isEqualTo("rca.notification_test");
            assertThat(payload.path("test").asBoolean()).isTrue();
            assertThat(payload.toString()).doesNotContain("test-delivery-token");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void testDeliverySkipsWhenNotificationIsDisabled() {
        IncidentNotificationService service = new IncidentNotificationService(
            new RcaConsoleProperties(),
            mock(AuditService.class),
            new ObjectMapper(),
            new RcaMetrics(new SimpleMeterRegistry())
        );

        NotificationTestResponse response = service.testDelivery();

        assertThat(response.outcome()).isEqualTo("skipped");
        assertThat(response.results()).isEmpty();
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
