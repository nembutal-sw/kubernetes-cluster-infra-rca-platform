package io.clusterinfra.rca.webconsole;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.clusterinfra.rca.webconsole.persistence.UserRepository;
import io.clusterinfra.rca.webconsole.persistence.UserSessionRepository;
import io.clusterinfra.rca.webconsole.service.RcaAnalysisWorker;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureObservability
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PlatformHttpTests {
    private static final String PUBLIC_API_BASE_URL = "https://rca.example.com";
    private static String accessToken;
    private static String clusterId;
    private static String bootstrapToken;
    private static String nodeToken;
    private static String evidenceRequestId;
    private static String reportId;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RcaAnalysisWorker analysisWorker;

    @Autowired
    private UserRepository users;

    @Autowired
    private UserSessionRepository sessions;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () ->
            "jdbc:h2:mem:platform-http-tests;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        registry.add("rca.public-api-base-url", () -> PUBLIC_API_BASE_URL);
        registry.add("rca.default-admin-username", () -> "admin");
        registry.add("rca.default-admin-password", () -> "admin");
        registry.add("rca.webhook-token", () -> "test-webhook-token");
        registry.add("spring.ai.model.chat", () -> "none");
        registry.add("rca.pipeline.initial-delay-ms", () -> "600000");
        registry.add("rca.observability.metrics-token", () -> "metrics-contract-token");
    }

    @Test
    @Order(1)
    void consolePageRendersUnifiedApiShellWithSecurityHeaders() {
        ResponseEntity<String> response = restTemplate.getForEntity("/", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType()).isNotNull();
        assertThat(response.getHeaders().getContentType().getCharset()).isEqualTo(StandardCharsets.UTF_8);
        assertThat(response.getBody())
            .contains("id=\"rca-console-root\"")
            .contains("type=\"module\"")
            .contains("/assets/index-");
        assertThat(response.getHeaders().getFirst("X-Frame-Options")).isEqualTo("DENY");
        assertThat(response.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getHeaders().getFirst("Content-Security-Policy"))
            .contains("default-src 'self'")
            .contains("connect-src 'self'")
            .contains("frame-ancestors 'none'");
    }

    @Test
    @Order(2)
    void protectedApiRejectsAnonymousRequests() {
        ResponseEntity<String> response = restTemplate.getForEntity("/api/clusters", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).contains("login required");

        ResponseEntity<String> webhook = restTemplate.postForEntity(
            "/api/webhooks/alertmanager",
            Map.of("alerts", List.of()),
            String.class
        );
        assertThat(webhook.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(webhook.getBody()).contains("invalid webhook token");

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Webhook-Token", "test-webhook-token");
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> authorizedWebhook = restTemplate.exchange(
            "/api/webhooks/alertmanager",
            HttpMethod.POST,
            new HttpEntity<>(Map.of("alerts", List.of()), headers),
            String.class
        );
        assertThat(authorizedWebhook.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @Order(3)
    void adminCanLoginAndCreateCluster() throws Exception {
        ResponseEntity<String> login = restTemplate.postForEntity(
            "/api/auth/login",
            Map.of("username", "admin", "password", "admin"),
            String.class
        );
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode loginBody = objectMapper.readTree(login.getBody());
        accessToken = loginBody.path("access_token").asText();
        assertThat(accessToken).isNotBlank();

        ResponseEntity<String> created = exchange(
            "/api/clusters",
            HttpMethod.POST,
            Map.of("name", "integration-cluster", "environment", "test")
        );
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode cluster = objectMapper.readTree(created.getBody());
        clusterId = cluster.path("cluster_id").asText();
        bootstrapToken = cluster.path("bootstrap_token").asText();
        assertThat(clusterId).startsWith("cluster-");
        assertThat(bootstrapToken).isNotBlank();
    }

    @Test
    @Order(4)
    void agentEvidenceSubmissionCreatesRcaReport() throws Exception {
        ResponseEntity<String> missingToken = restTemplate.postForEntity(
            "/api/agents/register",
            Map.of(
                "cluster_id", clusterId,
                "node_name", "worker-a",
                "agent_version", "0.1.0"
            ),
            String.class
        );
        assertThat(missingToken.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        ResponseEntity<String> wrongToken = restTemplate.postForEntity(
            "/api/agents/register",
            Map.of(
                "cluster_id", clusterId,
                "node_name", "worker-a",
                "agent_token", "wrong-token",
                "agent_version", "0.1.0"
            ),
            String.class
        );
        assertThat(wrongToken.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        HttpHeaders malformedHeaders = new HttpHeaders();
        malformedHeaders.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> malformed = restTemplate.exchange(
            "/api/agents/register",
            HttpMethod.POST,
            new HttpEntity<>("{broken", malformedHeaders),
            String.class
        );
        assertThat(malformed.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<String> registered = restTemplate.postForEntity(
            "/api/agents/register",
            Map.of(
                "cluster_id", clusterId,
                "node_name", "worker-a",
                "agent_token", bootstrapToken,
                "agent_version", "0.1.0",
                "supported_collectors", List.of("disk", "inode", "kernel")
            ),
            String.class
        );
        assertThat(registered.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode registeredBody = objectMapper.readTree(registered.getBody());
        nodeToken = registeredBody.path("node_token").asText();
        assertThat(registeredBody.path("agent_protocol_version").asText()).isEqualTo("1");

        ResponseEntity<String> tamperedIdentity = restTemplate.postForEntity(
            "/api/agents/heartbeat",
            Map.of(
                "cluster_id", clusterId,
                "node_name", "worker-b",
                "agent_token", bootstrapToken,
                "node_token", nodeToken
            ),
            String.class
        );
        assertThat(tamperedIdentity.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        ResponseEntity<String> request = exchange(
            "/api/evidence/requests",
            HttpMethod.POST,
            Map.of(
                "cluster_id", clusterId,
                "node_name", "worker-a",
                "alert_name", "DiskPressure",
                "requested_collectors", List.of("disk", "inode", "kernel")
            )
        );
        assertThat(request.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        evidenceRequestId = objectMapper.readTree(request.getBody()).path("request_id").asText();

        ResponseEntity<String> submitted = restTemplate.postForEntity(
            "/api/agents/evidence-responses",
            Map.of(
                "request_id", evidenceRequestId,
                "cluster_id", clusterId,
                "node_name", "worker-a",
                "agent_token", bootstrapToken,
                "node_token", nodeToken,
                "status", "completed",
                "collectors", Map.of(
                    "disk", Map.of("disk_usage_percent", 96.0, "await_ms", 38.0),
                    "inode", Map.of("inode_usage_percent", 98.0),
                    "kernel", Map.of("messages", List.of("EXT4-fs error: I/O error"))
                )
            ),
            String.class
        );
        assertThat(submitted.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(analysisWorker.processAvailableTasks()).isEqualTo(1);

        ResponseEntity<String> reports = exchange("/api/rca/reports", HttpMethod.GET, null);
        assertThat(reports.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode report = objectMapper.readTree(reports.getBody()).get(0);
        reportId = report.path("report_id").asText();
        assertThat(report.path("incident_id").asText()).startsWith("incident-");
        assertThat(report.path("summary").path("most_likely_cause").asText()).isNotBlank();
        assertThat(report.path("root_cause_candidates").size()).isGreaterThan(0);
        assertThat(report.path("recommended_actions").size()).isGreaterThan(0);
        assertThat(report.path("evidence").toString()).contains("derived_signals");
        JsonNode tasks = objectMapper.readTree(
            exchange("/api/rca/analysis-tasks", HttpMethod.GET, null).getBody()
        );
        assertThat(tasks).hasSize(1);
        assertThat(tasks.get(0).path("status").asText()).isEqualTo("completed");
        assertThat(tasks.get(0).path("report_id").asText()).isEqualTo(reportId);
    }

    @Test
    @Order(5)
    void manifestRequiresUserOrBootstrapToken() {
        String endpoint = "/api/clusters/" + clusterId + "/agent-manifest?backend_url=https://rca.example.com";
        ResponseEntity<String> anonymous = restTemplate.getForEntity(endpoint, String.class);
        assertThat(anonymous.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        ResponseEntity<String> invalidAgent = restTemplate.getForEntity(
            endpoint + "&agent_token=wrong-token",
            String.class
        );
        assertThat(invalidAgent.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        ResponseEntity<String> authorized = restTemplate.getForEntity(
            endpoint + "&agent_token=" + bootstrapToken,
            String.class
        );
        assertThat(authorized.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(authorized.getBody()).contains("\"kind\":\"DaemonSet\"");
        assertThat(authorized.getBody()).contains("KUBERNETES_TOPOLOGY_ENABLED");
        assertThat(authorized.getBody()).contains("endpointslices");

        ResponseEntity<String> authorizedUser = exchange(endpoint, HttpMethod.GET, null);
        assertThat(authorizedUser.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> topology = exchange(
            "/api/clusters/" + clusterId + "/topology",
            HttpMethod.GET,
            null
        );
        assertThat(topology.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(topology.getBody()).contains("\"cluster_id\":\"" + clusterId + "\"");
    }

    @Test
    @Order(6)
    void repeatedEvidenceIsCorrelatedWithoutCreatingAnotherReport() throws Exception {
        ResponseEntity<String> request = exchange(
            "/api/evidence/requests",
            HttpMethod.POST,
            Map.of(
                "cluster_id", clusterId,
                "node_name", "worker-a",
                "alert_name", "DiskPressure",
                "requested_collectors", List.of("disk", "inode", "kernel")
            )
        );
        String repeatedRequestId = objectMapper.readTree(request.getBody()).path("request_id").asText();
        ResponseEntity<String> submitted = restTemplate.postForEntity(
            "/api/agents/evidence-responses",
            Map.of(
                "request_id", repeatedRequestId,
                "cluster_id", clusterId,
                "node_name", "worker-a",
                "agent_token", bootstrapToken,
                "node_token", nodeToken,
                "status", "completed",
                "collectors", Map.of(
                    "disk", Map.of("disk_usage_percent", 96.0, "await_ms", 38.0),
                    "inode", Map.of("inode_usage_percent", 98.0),
                    "kernel", Map.of("messages", List.of("EXT4-fs error: I/O error"))
                )
            ),
            String.class
        );
        assertThat(submitted.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(analysisWorker.processAvailableTasks()).isEqualTo(1);

        JsonNode reports = objectMapper.readTree(
            exchange("/api/rca/reports", HttpMethod.GET, null).getBody()
        );
        assertThat(reports).hasSize(1);
        assertThat(reports.get(0).path("report_id").asText()).isEqualTo(reportId);

        JsonNode incidents = objectMapper.readTree(
            exchange("/api/rca/incidents", HttpMethod.GET, null).getBody()
        );
        assertThat(incidents).hasSize(1);
        assertThat(incidents.get(0).path("occurrence_count").asInt()).isEqualTo(2);

        HttpHeaders webhookHeaders = new HttpHeaders();
        webhookHeaders.setContentType(MediaType.APPLICATION_JSON);
        webhookHeaders.set("X-Webhook-Token", "test-webhook-token");
        ResponseEntity<String> resolvedWebhook = restTemplate.exchange(
            "/api/webhooks/alertmanager",
            HttpMethod.POST,
            new HttpEntity<>(Map.of(
                "status", "resolved",
                "alerts", List.of(Map.of(
                    "status", "resolved",
                    "labels", Map.of(
                        "alertname", "DiskPressure",
                        "cluster_id", clusterId,
                        "node", "worker-a"
                    ),
                    "endsAt", Instant.now().toString()
                ))
            ), webhookHeaders),
            String.class
        );
        assertThat(resolvedWebhook.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode resolvedIncident = objectMapper.readTree(
            exchange("/api/rca/incidents", HttpMethod.GET, null).getBody()
        ).get(0);
        assertThat(resolvedIncident.path("status").asText()).isEqualTo("resolved");
        assertThat(resolvedIncident.path("resolution_source").asText()).isEqualTo("alertmanager");
        assertThat(resolvedIncident.path("resolved_at").asText()).isNotBlank();
    }

    @Test
    @Order(7)
    void actionRequestsAndAuditHistoryAreRecorded() throws Exception {
        JsonNode report = objectMapper.readTree(
            exchange("/api/rca/reports/" + reportId, HttpMethod.GET, null).getBody()
        );
        JsonNode actions = report.path("recommended_actions");
        int safeActionIndex = actionIndex(actions, "AUTO_SAFE");
        int approvalActionIndex = actionIndex(actions, "APPROVAL_REQUIRED");

        JsonNode accepted = objectMapper.readTree(exchange(
            "/api/rca/reports/" + reportId + "/actions/" + safeActionIndex + "/execute",
            HttpMethod.POST,
            Map.of("confirmed", true, "note", "integration test")
        ).getBody());
        assertThat(accepted.path("status").asText()).isEqualTo("accepted");
        assertThat(accepted.path("execution_started").asBoolean()).isTrue();
        assertThat(accepted.path("action_request").path("status").asText()).isEqualTo("accepted");

        JsonNode pending = objectMapper.readTree(exchange(
            "/api/rca/reports/" + reportId + "/actions/" + approvalActionIndex + "/execute",
            HttpMethod.POST,
            Map.of("confirmed", true, "note", "request operator review")
        ).getBody());
        assertThat(pending.path("status").asText()).isEqualTo("pending_approval");
        assertThat(pending.path("execution_started").asBoolean()).isFalse();
        String actionRequestId = pending.path("action_request").path("action_request_id").asText();

        JsonNode approved = objectMapper.readTree(exchange(
            "/api/rca/action-requests/" + actionRequestId + "/approve",
            HttpMethod.POST,
            Map.of("confirmed", true, "note", "approved for manual execution")
        ).getBody());
        assertThat(approved.path("action_request").path("status").asText())
            .isEqualTo("approved_manual");
        assertThat(
            approved.path("action_execution").isNull()
                || approved.path("action_execution").isMissingNode()
        ).isTrue();

        JsonNode agentExecutions = objectMapper.readTree(restTemplate.postForEntity(
            "/api/agents/action-executions",
            Map.of(
                "cluster_id", clusterId,
                "node_name", "worker-a",
                "agent_token", bootstrapToken,
                "node_token", nodeToken,
                "limit", 1
            ),
            String.class
        ).getBody());
        assertThat(agentExecutions).isEmpty();
        ResponseEntity<String> rejectedAgentResult = restTemplate.postForEntity(
            "/api/agents/action-results",
            Map.of(
                "execution_id", "legacy-execution",
                "cluster_id", clusterId,
                "node_name", "worker-a",
                "agent_token", bootstrapToken,
                "node_token", nodeToken,
                "status", "completed",
                "exit_code", 0,
                "stdout", "",
                "stderr", "",
                "error_message", ""
            ),
            String.class
        );
        assertThat(rejectedAgentResult.getStatusCode()).isEqualTo(HttpStatus.GONE);

        JsonNode completed = objectMapper.readTree(exchange(
            "/api/rca/action-requests/" + actionRequestId + "/complete-manual",
            HttpMethod.POST,
            Map.of("confirmed", true, "note", "completed through external runbook")
        ).getBody());
        assertThat(completed.path("action_request").path("status").asText())
            .isEqualTo("completed");

        JsonNode auditEvents = objectMapper.readTree(
            exchange("/api/audit/events?limit=100", HttpMethod.GET, null).getBody()
        );
        assertThat(auditEvents.toString())
            .contains("rca.action_request")
            .contains("rca.action_manual_completed")
            .contains("incident.correlated")
            .contains("auth.login");

        String incidentId = report.path("incident_id").asText();
        JsonNode resolved = objectMapper.readTree(exchange(
            "/api/rca/incidents/" + incidentId + "/resolve",
            HttpMethod.POST,
            Map.of("confirmed", true, "note", "incident cleared")
        ).getBody());
        assertThat(resolved.path("status").asText()).isEqualTo("resolved");
    }

    @Test
    @Order(8)
    void realtimeAgentEventCreatesEvidenceReportAndTimeline() throws Exception {
        ResponseEntity<String> ingested = restTemplate.postForEntity(
            "/api/agents/realtime-events",
            Map.of(
                "cluster_id", clusterId,
                "node_name", "worker-a",
                "agent_token", bootstrapToken,
                "node_token", nodeToken,
                "events", List.of(Map.of(
                    "event_type", "oom_kill",
                    "component", "memory",
                    "severity", "critical",
                    "payload", Map.of("pid", 4242, "comm", "memory-hog")
                ))
            ),
            String.class
        );
        assertThat(ingested.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(objectMapper.readTree(ingested.getBody())).hasSize(1);
        assertThat(analysisWorker.processAvailableTasks()).isEqualTo(1);

        JsonNode reports = objectMapper.readTree(exchange("/api/rca/reports", HttpMethod.GET, null).getBody());
        JsonNode realtimeReport = null;
        for (JsonNode candidate : reports) {
            if ("OOMKillDetected".equals(candidate.path("summary").path("symptom").asText())) {
                realtimeReport = candidate;
                break;
            }
        }
        assertThat(realtimeReport).isNotNull();
        String incidentId = realtimeReport.path("incident_id").asText();
        JsonNode timeline = objectMapper.readTree(
            exchange("/api/rca/incidents/" + incidentId + "/timeline", HttpMethod.GET, null).getBody()
        );
        assertThat(timeline.path("nodes")).isNotEmpty();
        JsonNode oomNode = null;
        for (JsonNode node : timeline.path("nodes")) {
            if ("oom_kill".equals(node.path("event_type").asText())) {
                oomNode = node;
                break;
            }
        }
        assertThat(oomNode).isNotNull();
        assertThat(oomNode.path("root_trigger").asBoolean()).isTrue();
    }

    @Test
    @Order(9)
    void platformInfoExposesVersionedCompatibilityContract() throws Exception {
        JsonNode info = objectMapper.readTree(
            exchange("/api/v1/platform/info", HttpMethod.GET, null).getBody()
        );

        assertThat(info.path("platform_version").asText()).isEqualTo("0.1.0");
        assertThat(info.path("api_version").asText()).isEqualTo("v1");
        assertThat(info.path("agent_protocol_version").asText()).isEqualTo("1");
        assertThat(info.path("minimum_supported_agent_protocol_version").asText()).isEqualTo("1");
    }

    @Test
    @Order(10)
    void prometheusEndpointExportsRcaMetricsForAuthenticatedOperator() {
        ResponseEntity<String> response = exchange("/actuator/prometheus", HttpMethod.GET, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType().toString()).contains("text/plain");
        assertThat(response.getBody())
            .contains("rca_agent_offline_count")
            .contains("rca_analysis_queue_depth")
            .contains("rca_report_generation");

        HttpHeaders metricsHeaders = new HttpHeaders();
        metricsHeaders.set("X-Metrics-Token", "metrics-contract-token");
        ResponseEntity<String> scraperResponse = restTemplate.exchange(
            "/actuator/prometheus",
            HttpMethod.GET,
            new HttpEntity<>(null, metricsHeaders),
            String.class
        );
        assertThat(scraperResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @Order(11)
    void expiredSessionCannotAccessProtectedApi() {
        String expiredToken = sessions.create(
            users.authenticate("admin", "admin").orElseThrow().userId(),
            Instant.now().minusSeconds(1)
        );
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(expiredToken);

        ResponseEntity<String> response = restTemplate.exchange(
            "/api/clusters",
            HttpMethod.GET,
            new HttpEntity<>(null, headers),
            String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).contains("login required");
    }

    private int actionIndex(JsonNode actions, String policy) {
        for (int index = 0; index < actions.size(); index++) {
            if (policy.equals(actions.get(index).path("policy").asText())) {
                return index;
            }
        }
        throw new AssertionError("No action found for policy " + policy);
    }

    private ResponseEntity<String> exchange(String path, HttpMethod method, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(path, method, new HttpEntity<>(body, headers), String.class);
    }
}
