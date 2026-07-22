package io.clusterinfra.rca.webconsole;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.clusterinfra.rca.webconsole.persistence.UserRepository;
import io.clusterinfra.rca.webconsole.persistence.UserSessionRepository;
import io.clusterinfra.rca.webconsole.service.RcaAnalysisWorker;
import java.net.URLDecoder;
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
        registry.add("rca.security.standard-request-max-bytes", () -> "1024");
        registry.add("rca.security.evidence-request-max-bytes", () -> "4096");
        registry.add("rca.export.signature-secret", () -> "platform-info-signing-secret");
        registry.add("rca.export.signature-key-id", () -> "platform-info-key");
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

        for (String path : List.of(
            "/overview",
            "/clusters/cluster-direct-route",
            "/reports/report-direct-route",
            "/incidents/incident-direct-route",
            "/settings"
        )) {
            ResponseEntity<String> directRoute = restTemplate.getForEntity(path, String.class);
            assertThat(directRoute.getStatusCode()).as(path).isEqualTo(HttpStatus.OK);
            assertThat(directRoute.getBody()).as(path).contains("id=\"rca-console-root\"");
        }
    }

    @Test
    @Order(2)
    void protectedApiRejectsAnonymousRequests() {
        ResponseEntity<String> response = restTemplate.getForEntity("/api/clusters", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody())
            .contains("login required")
            .contains("\"code\":\"authentication_required\"")
            .contains("\"title\":\"Authentication required\"")
            .contains("\"suggestion\"")
            .contains("\"trace_id\"");
        assertThat(response.getHeaders().getFirst("X-Request-ID")).isNotBlank();

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
        HttpHeaders loginHeaders = new HttpHeaders();
        loginHeaders.setContentType(MediaType.APPLICATION_JSON);
        loginHeaders.set("X-Forwarded-For", "203.0.113.10");
        loginHeaders.set("User-Agent", "PlatformHttpTests/1.0");
        ResponseEntity<String> login = restTemplate.exchange(
            "/api/auth/login",
            HttpMethod.POST,
            new HttpEntity<>(Map.of("username", "admin", "password", "admin"), loginHeaders),
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

        ResponseEntity<String> thresholds = exchange(
            "/api/clusters/" + clusterId + "/thresholds",
            HttpMethod.PUT,
            Map.of(
                "thresholds", Map.of(
                    "disk.warning.percent", 93.0,
                    "disk.critical.percent", 95.0
                ),
                "reason", "integration threshold override"
            )
        );
        assertThat(thresholds.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode thresholdBody = objectMapper.readTree(thresholds.getBody());
        assertThat(thresholdBody.path("overrides").path("disk.critical.percent").asDouble()).isEqualTo(95.0);
        assertThat(thresholdBody.path("effective").path("disk.warning.percent").asDouble()).isEqualTo(93.0);

        ResponseEntity<String> missing = exchange(
            "/api/clusters/cluster-does-not-exist",
            HttpMethod.GET,
            null
        );
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(missing.getBody())
            .contains("\"code\":\"resource_not_found\"")
            .contains("\"trace_id\"");

        HttpHeaders malformedHeaders = new HttpHeaders();
        malformedHeaders.setBearerAuth(accessToken);
        malformedHeaders.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> malformed = restTemplate.exchange(
            "/api/clusters",
            HttpMethod.POST,
            new HttpEntity<>("{broken", malformedHeaders),
            String.class
        );
        assertThat(malformed.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(malformed.getBody())
            .contains("\"code\":\"malformed_json\"")
            .contains("\"trace_id\"");

        ResponseEntity<String> unknownApi = exchange("/api/not-a-real-resource", HttpMethod.GET, null);
        assertThat(unknownApi.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(unknownApi.getBody()).contains("\"code\":\"resource_not_found\"");
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

        ResponseEntity<String> aggregateHealth = exchange("/api/v1/agent-health", HttpMethod.GET, null);
        assertThat(aggregateHealth.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode healthItems = objectMapper.readTree(aggregateHealth.getBody());
        assertThat(healthItems).hasSize(1);
        assertThat(healthItems.get(0).path("cluster_id").asText()).isEqualTo(clusterId);
        assertThat(healthItems.get(0).path("node_name").asText()).isEqualTo("worker-a");

        JsonNode filteredHealth = objectMapper.readTree(exchange(
            "/api/v1/agent-health?cluster_ids=" + clusterId,
            HttpMethod.GET,
            null
        ).getBody());
        assertThat(filteredHealth).hasSize(1);

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

        Map<String, Object> evidenceResponse = Map.of(
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
            );
        ResponseEntity<String> submitted = restTemplate.postForEntity(
            "/api/agents/evidence-responses",
            evidenceResponse,
            String.class
        );
        assertThat(submitted.getStatusCode()).isEqualTo(HttpStatus.OK);
        String submittedEvidenceId = objectMapper.readTree(submitted.getBody())
            .path("evidence_id")
            .asText();
        ResponseEntity<String> duplicate = restTemplate.postForEntity(
            "/api/agents/evidence-responses",
            evidenceResponse,
            String.class
        );
        assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(objectMapper.readTree(duplicate.getBody()).path("evidence_id").asText())
            .isEqualTo(submittedEvidenceId);
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
        assertThat(report.path("evidence").toString()).contains("\"threshold\":95.0");
        JsonNode tasks = objectMapper.readTree(
            exchange("/api/rca/analysis-tasks", HttpMethod.GET, null).getBody()
        );
        assertThat(tasks).hasSize(1);
        assertThat(tasks.get(0).path("status").asText()).isEqualTo("completed");
        assertThat(tasks.get(0).path("report_id").asText()).isEqualTo(reportId);
    }

    @Test
    @Order(5)
    void manifestRequiresUserOrOneTimeDownloadToken() throws Exception {
        String endpoint = "/api/clusters/" + clusterId + "/agent-manifest?backend_url=https://rca.example.com";
        ResponseEntity<String> anonymous = restTemplate.getForEntity(endpoint, String.class);
        assertThat(anonymous.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        ResponseEntity<String> bootstrapTokenInQuery = restTemplate.getForEntity(
            endpoint + "&agent_token=" + bootstrapToken,
            String.class
        );
        assertThat(bootstrapTokenInQuery.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        ResponseEntity<String> install = exchange(
            "/api/clusters/" + clusterId
                + "/install-command?backend_url=https://rca.example.com",
            HttpMethod.GET,
            null
        );
        JsonNode installBody = objectMapper.readTree(install.getBody());
        String manifestCommand = installBody.path("commands").get(2).asText();
        String encodedToken = manifestCommand.replaceAll(
            ".*[?&]manifest_token=([^&\\\"]+).*",
            "$1"
        );
        String manifestToken = URLDecoder.decode(encodedToken, StandardCharsets.UTF_8);

        ResponseEntity<String> authorized = restTemplate.getForEntity(
            endpoint + "&manifest_token=" + manifestToken,
            String.class
        );
        assertThat(authorized.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(authorized.getBody()).contains("\"kind\":\"DaemonSet\"");
        assertThat(authorized.getBody()).contains("\"kind\":\"Secret\"");
        assertThat(authorized.getBody()).contains("\"cluster-id\"");
        assertThat(authorized.getBody()).contains("\"agent-token\"");
        assertThat(authorized.getBody()).contains("KUBERNETES_TOPOLOGY_ENABLED");
        assertThat(authorized.getBody()).contains("endpointslices");

        ResponseEntity<String> reused = restTemplate.getForEntity(
            endpoint + "&manifest_token=" + manifestToken,
            String.class
        );
        assertThat(reused.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

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
    @Order(50)
    void oversizedRequestBodiesAreRejectedBeforeAuthentication() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String oversized = "{\"value\":\"" + "x".repeat(5000) + "\"}";
        ResponseEntity<String> response = restTemplate.exchange(
            "/api/auth/login",
            HttpMethod.POST,
            new HttpEntity<>(oversized, headers),
            String.class
        );
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(response.getBody()).contains("request body exceeds 1024 bytes");
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
        JsonNode loginAuditEvents = objectMapper.readTree(
            exchange(
                "/api/audit/events?event_type=auth.login&client_ip=203.0.113.10&q=PlatformHttpTests&limit=20",
                HttpMethod.GET,
                null
            ).getBody()
        );
        assertThat(loginAuditEvents).hasSize(1);
        JsonNode loginDetails = loginAuditEvents.get(0).path("details");
        assertThat(loginDetails.path("client_ip").asText()).isEqualTo("203.0.113.10");
        assertThat(loginDetails.path("client_ip_source").asText()).isIn("X-Forwarded-For", "remote_addr");
        assertThat(loginDetails.path("user_agent").asText()).isEqualTo("PlatformHttpTests/1.0");
        assertThat(loginDetails.path("method").asText()).isEqualTo("POST");
        assertThat(loginDetails.path("path").asText()).isEqualTo("/api/auth/login");

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
        assertThat(info.path("agent_protocol_version").asText()).isEqualTo("2");
        assertThat(info.path("minimum_supported_agent_protocol_version").asText()).isEqualTo("1");
        JsonNode exportSecurity = info.path("export_security");
        assertThat(exportSecurity.path("hash_algorithm").asText()).isEqualTo("SHA-256");
        assertThat(exportSecurity.path("bundle_signature_enabled").asBoolean()).isTrue();
        assertThat(exportSecurity.path("bundle_signature_algorithm").asText()).isEqualTo("HMAC-SHA256");
        assertThat(exportSecurity.path("bundle_signature_key_id").asText()).isEqualTo("platform-info-key");
        assertThat(exportSecurity.path("offline_verifier").asText()).isEqualTo("scripts/verify_evidence_bundle.py");
        JsonNode llm = info.path("llm");
        assertThat(llm.path("enabled").asBoolean()).isFalse();
        assertThat(llm.path("provider").asText()).isEqualTo("none");
        assertThat(llm.path("spring_ai_chat_model").asText()).isEqualTo("none");
        assertThat(llm.path("credential_configured").asBoolean()).isFalse();
        JsonNode notification = info.path("notification");
        assertThat(notification.path("enabled").asBoolean()).isFalse();
        assertThat(notification.path("slack_configured").asBoolean()).isFalse();
        assertThat(notification.path("webhook_configured").asBoolean()).isFalse();
        assertThat(notification.path("webhook_token_configured").asBoolean()).isFalse();
        assertThat(notification.path("channels")).isEmpty();
        JsonNode catalogSummary = info.path("catalog");
        assertThat(catalogSummary.path("schema_version").asText()).isEqualTo("rca-catalog/v1");
        assertThat(catalogSummary.path("action_plan_execution_enabled").asBoolean()).isFalse();
        assertThat(catalogSummary.path("collector_count").asInt()).isGreaterThan(0);
        JsonNode thresholds = info.path("thresholds");
        assertThat(thresholds.path("cluster_override_enabled").asBoolean()).isTrue();
        assertThat(thresholds.path("definitions")).isNotEmpty();
        assertThat(info.toString()).doesNotContain("platform-info-signing-secret");
        assertThat(info.toString()).doesNotContain("api-key");

        JsonNode catalog = objectMapper.readTree(
            exchange("/api/v1/catalog", HttpMethod.GET, null).getBody()
        );
        assertThat(catalog.path("summary").path("schema_version").asText()).isEqualTo("rca-catalog/v1");
        assertThat(catalog.path("collectors").path("disk").path("enabled").asBoolean()).isTrue();
        assertThat(catalog.path("actions").path("restart_kubelet").path("plan").path("executable").asBoolean()).isFalse();
        assertThat(catalog.path("rules").path("disk-pressure").path("enabled").asBoolean()).isTrue();

        JsonNode validPreview = objectMapper.readTree(exchange(
            "/api/v1/catalog/preview",
            HttpMethod.POST,
            Map.of(
                "override_json",
                """
                    {
                      "schema_version": "rca-catalog/v1",
                      "version": "platform-preview",
                      "rules": {
                        "disk-pressure": {"enabled": false}
                      }
                    }
                    """,
                "reason", "platform contract test"
            )
        ).getBody());
        assertThat(validPreview.path("valid").asBoolean()).isTrue();
        assertThat(validPreview.path("summary").path("version").asText()).isEqualTo("platform-preview");
        assertThat(validPreview.path("diff")).isNotEmpty();
        assertThat(validPreview.toString()).contains("/rules/disk-pressure/enabled");

        JsonNode unsafePreview = objectMapper.readTree(exchange(
            "/api/v1/catalog/preview",
            HttpMethod.POST,
            Map.of(
                "override_json",
                """
                    {
                      "schema_version": "rca-catalog/v1",
                      "actions": {
                        "restart_kubelet": {
                          "plan": {"executable": true}
                        }
                      }
                    }
                    """,
                "reason", "unsafe preview contract test"
            )
        ).getBody());
        assertThat(unsafePreview.path("valid").asBoolean()).isFalse();
        assertThat(unsafePreview.path("message").asText()).contains("plan.executable must be false");

        ResponseEntity<String> unsafeDraft = exchange(
            "/api/v1/catalog/overrides/drafts",
            HttpMethod.POST,
            Map.of(
                "override_json",
                """
                    {
                      "schema_version": "rca-catalog/v1",
                      "actions": {
                        "restart_kubelet": {
                          "plan": {"executable": true}
                        }
                      }
                    }
                    """,
                "reason", "unsafe draft contract test"
            )
        );
        assertThat(unsafeDraft.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        JsonNode draft = objectMapper.readTree(exchange(
            "/api/v1/catalog/overrides/drafts",
            HttpMethod.POST,
            Map.of(
                "override_json",
                """
                    {
                      "schema_version": "rca-catalog/v1",
                      "version": "platform-draft",
                      "rules": {
                        "disk-pressure": {"enabled": false}
                      }
                    }
                    """,
                "reason", "platform draft contract test"
            )
        ).getBody());
        String draftId = draft.path("draft_id").asText();
        assertThat(draft.path("status").asText()).isEqualTo("draft");
        assertThat(draft.path("diff")).isNotEmpty();

        JsonNode approvedDraft = objectMapper.readTree(exchange(
            "/api/v1/catalog/overrides/drafts/" + draftId + "/approve",
            HttpMethod.POST,
            Map.of("confirmed", true, "note", "approved for GitOps handoff")
        ).getBody());
        assertThat(approvedDraft.path("status").asText()).isEqualTo("approved");

        JsonNode handoff = objectMapper.readTree(exchange(
            "/api/v1/catalog/overrides/drafts/" + draftId + "/handoff",
            HttpMethod.GET,
            null
        ).getBody());
        assertThat(handoff.path("recommendation").asText()).contains("GitOps");
        assertThat(handoff.path("pull_request_body").asText()).contains("/rules/disk-pressure/enabled");
        assertThat(handoff.path("files").path("ops/catalog/operational-catalog.override.json").asText())
            .contains("platform-draft");

        JsonNode catalogAudit = objectMapper.readTree(
            exchange("/api/audit/events?event_type=catalog.override.preview&limit=10", HttpMethod.GET, null).getBody()
        );
        assertThat(catalogAudit.toString()).contains("success").contains("rejected");
        JsonNode draftAudit = objectMapper.readTree(
            exchange("/api/audit/events?event_type=catalog.override_draft&limit=10", HttpMethod.GET, null).getBody()
        );
        assertThat(draftAudit.toString()).contains("created").contains("approved");
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
    void adminCanChangeLoginIdAfterDefaultCredentialLogin() throws Exception {
        ResponseEntity<String> invalidPassword = exchange(
            "/api/auth/change-login-id",
            HttpMethod.POST,
            Map.of("current_password", "wrong-password", "new_username", "platform-admin")
        );
        assertThat(invalidPassword.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        ResponseEntity<String> changed = exchange(
            "/api/auth/change-login-id",
            HttpMethod.POST,
            Map.of("current_password", "admin", "new_username", "platform-admin")
        );
        assertThat(changed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(objectMapper.readTree(changed.getBody()).path("email").asText()).isEqualTo("platform-admin");
        assertThat(users.authenticate("platform-admin", "admin")).isPresent();
        assertThat(users.authenticate("admin", "admin")).isEmpty();
        assertThat(users.ensureDefaultAdmin("admin", "admin").email()).isEqualTo("platform-admin");
        assertThat(users.authenticate("admin", "admin")).isEmpty();

        ResponseEntity<String> restored = exchange(
            "/api/auth/change-login-id",
            HttpMethod.POST,
            Map.of("current_password", "admin", "new_username", "admin")
        );
        assertThat(restored.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(objectMapper.readTree(restored.getBody()).path("email").asText()).isEqualTo("admin");
    }

    @Test
    @Order(12)
    void adminCanChangePasswordAfterDefaultCredentialLogin() throws Exception {
        ResponseEntity<String> invalidPassword = exchange(
            "/api/auth/change-password",
            HttpMethod.POST,
            Map.of("current_password", "wrong-password", "new_password", "admin-password-1")
        );
        assertThat(invalidPassword.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        ResponseEntity<String> tooShort = exchange(
            "/api/auth/change-password",
            HttpMethod.POST,
            Map.of("current_password", "admin", "new_password", "short")
        );
        assertThat(tooShort.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

        ResponseEntity<String> changed = exchange(
            "/api/auth/change-password",
            HttpMethod.POST,
            Map.of("current_password", "admin", "new_password", "admin-password-1")
        );
        assertThat(changed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(objectMapper.readTree(changed.getBody()).path("changed").asBoolean()).isTrue();
        assertThat(users.authenticate("admin", "admin")).isEmpty();
        assertThat(users.authenticate("admin", "admin-password-1")).isPresent();

        assertThat(users.changePassword("user-admin", "admin-password-1", "admin")).isTrue();
        assertThat(users.authenticate("admin", "admin")).isPresent();
    }

    @Test
    @Order(13)
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

    @Test
    @Order(14)
    void paginatedEvidenceAndAuditExportsUseBoundedOperationalApis() throws Exception {
        ResponseEntity<String> requests = exchange(
            "/api/clusters/" + clusterId + "/evidence-requests?limit=1",
            HttpMethod.GET,
            null
        );
        assertThat(requests.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(objectMapper.readTree(requests.getBody())).hasSize(1);

        ResponseEntity<String> invalidLimit = exchange(
            "/api/clusters/" + clusterId + "/evidence-requests?limit=201",
            HttpMethod.GET,
            null
        );
        assertThat(invalidLimit.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<String> jsonExport = exchange(
            "/api/audit/events/export?format=json&limit=100",
            HttpMethod.GET,
            null
        );
        assertThat(jsonExport.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jsonExport.getHeaders().getContentDisposition().isAttachment()).isTrue();
        assertThat(objectMapper.readTree(jsonExport.getBody()).path("events").isArray()).isTrue();

        ResponseEntity<String> csvExport = exchange(
            "/api/audit/events/export?format=csv&limit=100",
            HttpMethod.GET,
            null
        );
        assertThat(csvExport.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(csvExport.getBody()).startsWith("created_at,actor_type");

        ResponseEntity<String> invalidExport = exchange(
            "/api/audit/events/export?format=xml&limit=100",
            HttpMethod.GET,
            null
        );
        assertThat(invalidExport.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    @Order(15)
    void evidenceBundleManifestSummaryIsAvailableForOperators() throws Exception {
        ResponseEntity<String> manifest = exchange(
            "/api/rca/reports/" + reportId + "/bundle/manifest",
            HttpMethod.GET,
            null
        );
        assertThat(manifest.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = objectMapper.readTree(manifest.getBody());
        assertThat(body.path("report_id").asText()).isEqualTo(reportId);
        assertThat(body.path("filename").asText()).startsWith("rca-evidence-bundle-");
        assertThat(body.path("hash_algorithm").asText()).isEqualTo("SHA-256");
        assertThat(body.path("entry_count").asInt()).isGreaterThanOrEqualTo(5);
        assertThat(body.path("entries")).isNotEmpty();
        assertThat(body.path("signature_enabled").asBoolean()).isTrue();
        assertThat(body.path("signature_key_id").asText()).isEqualTo("platform-info-key");
        assertThat(body.path("verification_command").asText())
            .contains("verify_evidence_bundle.py")
            .contains("--require-signature");
        assertThat(manifest.getBody()).doesNotContain("platform-info-signing-secret");
    }

    @Test
    @Order(16)
    void adminCanRotateAgentBootstrapTokenAndOldTokenStopsWorking() throws Exception {
        ResponseEntity<String> rotation = exchange(
            "/api/clusters/" + clusterId + "/agent-token/rotate",
            HttpMethod.POST,
            null
        );
        assertThat(rotation.getStatusCode()).isEqualTo(HttpStatus.OK);
        String rotatedToken = objectMapper.readTree(rotation.getBody())
            .path("agent_token")
            .asText();
        assertThat(rotatedToken).isNotBlank().isNotEqualTo(bootstrapToken);

        ResponseEntity<String> oldToken = restTemplate.postForEntity(
            "/api/agents/register",
            Map.of(
                "cluster_id", clusterId,
                "node_name", "rotation-test",
                "agent_token", bootstrapToken,
                "agent_version", "0.1.0",
                "supported_collectors", List.of("node")
            ),
            String.class
        );
        assertThat(oldToken.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        ResponseEntity<String> newToken = restTemplate.postForEntity(
            "/api/agents/register",
            Map.of(
                "cluster_id", clusterId,
                "node_name", "rotation-test",
                "agent_token", rotatedToken,
                "agent_version", "0.1.0",
                "supported_collectors", List.of("node")
            ),
            String.class
        );
        assertThat(newToken.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        bootstrapToken = rotatedToken;
    }

    @Test
    @Order(17)
    void adminCanInspectAndTestNotificationDeliveryContract() throws Exception {
        ResponseEntity<String> status = exchange("/api/notifications/status", HttpMethod.GET, null);
        assertThat(status.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode statusBody = objectMapper.readTree(status.getBody());
        assertThat(statusBody.path("enabled").asBoolean()).isFalse();
        assertThat(statusBody.path("channels")).isEmpty();

        ResponseEntity<String> missingConfirmation = exchange(
            "/api/notifications/test",
            HttpMethod.POST,
            Map.of("confirmed", false)
        );
        assertThat(missingConfirmation.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<String> test = exchange(
            "/api/notifications/test",
            HttpMethod.POST,
            Map.of("confirmed", true)
        );
        assertThat(test.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode testBody = objectMapper.readTree(test.getBody());
        assertThat(testBody.path("outcome").asText()).isEqualTo("skipped");
        assertThat(testBody.path("results")).isEmpty();

        JsonNode history = objectMapper.readTree(
            exchange("/api/notifications/history?limit=10", HttpMethod.GET, null).getBody()
        );
        assertThat(history).isNotEmpty();
        assertThat(history.get(0).path("event_type").asText()).isEqualTo("notification.test");
        assertThat(history.get(0).path("outcome").asText()).isEqualTo("skipped");
        assertThat(history.get(0).path("details").path("client_ip").asText()).isNotBlank();
    }

    @Test
    @Order(18)
    void adminCanInspectAndTestLlmDiagnosticsContract() throws Exception {
        ResponseEntity<String> diagnostics = exchange("/api/llm/diagnostics", HttpMethod.GET, null);
        assertThat(diagnostics.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode diagnosticsBody = objectMapper.readTree(diagnostics.getBody());
        assertThat(diagnosticsBody.path("outcome").asText()).isEqualTo("disabled");
        assertThat(diagnosticsBody.path("configuration").path("enabled").asBoolean()).isFalse();
        assertThat(diagnosticsBody.path("checks")).isNotEmpty();
        assertThat(diagnostics.getBody())
            .doesNotContain("platform-info-signing-secret")
            .doesNotContain("api-key");

        ResponseEntity<String> setup = exchange("/api/llm/setup", HttpMethod.GET, null);
        assertThat(setup.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode setupBody = objectMapper.readTree(setup.getBody());
        assertThat(setupBody.path("docs_path").asText()).isEqualTo("docs/llm-analyzer.md");
        assertThat(setupBody.path("restart_required").asBoolean()).isTrue();
        assertThat(setupBody.path("providers")).isNotEmpty();
        assertThat(setup.getBody())
            .contains("SPRING_AI_OPENAI_SDK_API_KEY")
            .contains("SPRING_AI_OLLAMA_BASE_URL")
            .contains("gemini-3.1-flash-lite")
            .contains("self_hosted")
            .doesNotContain("sk-")
            .doesNotContain("platform-info-signing-secret");

        ResponseEntity<String> missingConfirmation = exchange(
            "/api/llm/test",
            HttpMethod.POST,
            Map.of("confirmed", false)
        );
        assertThat(missingConfirmation.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<String> test = exchange(
            "/api/llm/test",
            HttpMethod.POST,
            Map.of("confirmed", true)
        );
        assertThat(test.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode testBody = objectMapper.readTree(test.getBody());
        assertThat(testBody.path("outcome").asText()).isEqualTo("skipped");
        assertThat(testBody.path("prompt_version").asText()).isEqualTo("llm-connectivity-test/v1");
        assertThat(testBody.path("error").asText()).isBlank();

        JsonNode events = objectMapper.readTree(
            exchange("/api/audit/events?event_type=llm.test&limit=5", HttpMethod.GET, null).getBody()
        );
        assertThat(events).isNotEmpty();
        assertThat(events.get(0).path("event_type").asText()).isEqualTo("llm.test");
        assertThat(events.get(0).path("outcome").asText()).isEqualTo("skipped");
        assertThat(events.get(0).path("details").path("client_ip").asText()).isNotBlank();
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
