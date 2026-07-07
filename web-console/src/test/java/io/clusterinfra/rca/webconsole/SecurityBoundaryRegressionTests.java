package io.clusterinfra.rca.webconsole;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.clusterinfra.rca.webconsole.security.AgentAuthenticationFilter;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureObservability
class SecurityBoundaryRegressionTests {
    private static final String WEBHOOK_TOKEN = "security-boundary-webhook-token";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String databaseName = "security-boundary-" + UUID.randomUUID();
        registry.add("spring.datasource.url", () ->
            "jdbc:h2:mem:" + databaseName + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        registry.add("rca.default-admin-username", () -> "admin");
        registry.add("rca.default-admin-password", () -> "admin");
        registry.add("rca.webhook-token", () -> WEBHOOK_TOKEN);
        registry.add("spring.ai.model.chat", () -> "none");
        registry.add("rca.pipeline.initial-delay-ms", () -> "600000");
        registry.add("rca.export.signature-secret", () -> "security-boundary-signing-secret");
        registry.add("rca.export.signature-key-id", () -> "security-boundary-key");
    }

    @Test
    void webhookRequiresConfiguredTokenAndRecordsAuthFailuresWithRequestContext() {
        long before = auditCount("webhook.auth_failed");

        ResponseEntity<String> missing = webhookRequest(null, null, "198.51.100.10");
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(missing.getBody()).contains("invalid webhook token");

        ResponseEntity<String> wrongHeader = webhookRequest("wrong-token", null, "198.51.100.11");
        assertThat(wrongHeader.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        ResponseEntity<String> wrongBearer = webhookRequest(null, "wrong-token", "198.51.100.12");
        assertThat(wrongBearer.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        ResponseEntity<String> headerAuthorized = webhookRequest(WEBHOOK_TOKEN, null, "198.51.100.13");
        assertThat(headerAuthorized.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> bearerAuthorized = webhookRequest(null, WEBHOOK_TOKEN, "198.51.100.14");
        assertThat(bearerAuthorized.getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(auditCount("webhook.auth_failed")).isEqualTo(before + 3);
        assertThat(latestAuditDetails("webhook.auth_failed"))
            .contains("client_ip")
            .contains("198.51.100.12")
            .contains("user_agent")
            .contains("SecurityBoundaryRegressionTests/1.0");
    }

    @Test
    void everyAgentEndpointRequiresAgentCredentialsBeforeControllerLogic() {
        long before = auditCount("agent.auth_failed");

        AgentAuthenticationFilter.protectedPaths().stream()
            .sorted(Comparator.naturalOrder())
            .forEach(path -> {
                ResponseEntity<String> response = restTemplate.exchange(
                    path,
                    HttpMethod.POST,
                    jsonEntity(Map.of()),
                    String.class
                );
                assertThat(response.getStatusCode())
                    .as("agent endpoint %s should reject missing credentials: %s", path, response.getBody())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
                assertThat(response.getBody()).contains("agent credentials required");
            });

        assertThat(auditCount("agent.auth_failed"))
            .isGreaterThanOrEqualTo(before + AgentAuthenticationFilter.protectedPaths().size());
        assertThat(latestAuditDetails("agent.auth_failed"))
            .contains("path")
            .contains("/api/agents/");
    }

    @Test
    void agentRegisterAndHeartbeatRejectTamperedTokensButAcceptValidIdentity() throws Exception {
        ClusterFixture cluster = createCluster();
        long before = auditCount("agent.auth_failed");

        ResponseEntity<String> wrongBootstrap = restTemplate.exchange(
            "/api/agents/register",
            HttpMethod.POST,
            jsonEntity(Map.of(
                "cluster_id", cluster.clusterId(),
                "node_name", "worker-a",
                "agent_token", "wrong-token",
                "agent_version", "0.1.0"
            )),
            String.class
        );
        assertThat(wrongBootstrap.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(wrongBootstrap.getBody()).contains("invalid agent token");

        ResponseEntity<String> registered = restTemplate.exchange(
            "/api/agents/register",
            HttpMethod.POST,
            jsonEntity(Map.of(
                "cluster_id", cluster.clusterId(),
                "node_name", "worker-a",
                "agent_token", cluster.bootstrapToken(),
                "agent_version", "0.1.0",
                "supported_collectors", List.of("disk", "kernel")
            )),
            String.class
        );
        assertThat(registered.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String nodeToken = objectMapper.readTree(registered.getBody()).path("node_token").asText();
        assertThat(nodeToken).isNotBlank();

        ResponseEntity<String> missingNodeToken = restTemplate.exchange(
            "/api/agents/heartbeat",
            HttpMethod.POST,
            jsonEntity(Map.of(
                "cluster_id", cluster.clusterId(),
                "node_name", "worker-a",
                "agent_token", cluster.bootstrapToken()
            )),
            String.class
        );
        assertThat(missingNodeToken.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(missingNodeToken.getBody()).contains("agent credentials required");

        ResponseEntity<String> wrongNodeToken = restTemplate.exchange(
            "/api/agents/heartbeat",
            HttpMethod.POST,
            jsonEntity(Map.of(
                "cluster_id", cluster.clusterId(),
                "node_name", "worker-a",
                "agent_token", cluster.bootstrapToken(),
                "node_token", "wrong-node-token"
            )),
            String.class
        );
        assertThat(wrongNodeToken.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(wrongNodeToken.getBody()).contains("invalid node token");

        ResponseEntity<String> validHeartbeat = restTemplate.exchange(
            "/api/agents/heartbeat",
            HttpMethod.POST,
            jsonEntity(Map.of(
                "cluster_id", cluster.clusterId(),
                "node_name", "worker-a",
                "agent_token", cluster.bootstrapToken(),
                "node_token", nodeToken,
                "status", "healthy"
            )),
            String.class
        );
        assertThat(validHeartbeat.getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(auditCount("agent.auth_failed")).isGreaterThanOrEqualTo(before + 3);
        assertThat(latestAuditDetails("agent.auth_failed"))
            .contains("reason")
            .contains("invalid node token")
            .contains("client_ip");
    }

    @Test
    void manifestAccessRejectsAgentTokenAndAllowsOnlyUserOrOneTimeManifestToken() throws Exception {
        ClusterFixture cluster = createCluster();
        String endpoint = "/api/clusters/" + cluster.clusterId() + "/agent-manifest?backend_url=https://rca.example.com";
        long before = auditCount("manifest.auth_failed");

        ResponseEntity<String> anonymous = restTemplate.getForEntity(endpoint, String.class);
        assertThat(anonymous.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        ResponseEntity<String> bootstrapTokenInQuery = restTemplate.getForEntity(
            endpoint + "&agent_token=" + cluster.bootstrapToken(),
            String.class
        );
        assertThat(bootstrapTokenInQuery.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        ResponseEntity<String> wrongManifestToken = restTemplate.getForEntity(
            endpoint + "&manifest_token=wrong-manifest-token",
            String.class
        );
        assertThat(wrongManifestToken.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        ResponseEntity<String> authorizedUser = exchange(
            cluster.adminToken(),
            HttpMethod.GET,
            endpoint,
            null
        );
        assertThat(authorizedUser.getStatusCode()).isEqualTo(HttpStatus.OK);

        String manifestToken = issueManifestToken(cluster);
        ResponseEntity<String> authorizedToken = restTemplate.getForEntity(
            endpoint + "&manifest_token=" + manifestToken,
            String.class
        );
        assertThat(authorizedToken.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(authorizedToken.getBody()).contains("\"kind\":\"DaemonSet\"");

        ResponseEntity<String> reusedToken = restTemplate.getForEntity(
            endpoint + "&manifest_token=" + manifestToken,
            String.class
        );
        assertThat(reusedToken.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        assertThat(auditCount("manifest.auth_failed")).isEqualTo(before + 4);
        assertThat(latestAuditDetails("manifest.auth_failed"))
            .contains("query_keys")
            .contains("manifest_token")
            .contains("query_values_redacted")
            .doesNotContain(manifestToken);
    }

    private ResponseEntity<String> webhookRequest(String xWebhookToken, String bearerToken, String forwardedFor) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Forwarded-For", forwardedFor);
        headers.set("User-Agent", "SecurityBoundaryRegressionTests/1.0");
        if (xWebhookToken != null) {
            headers.set("X-Webhook-Token", xWebhookToken);
        }
        if (bearerToken != null) {
            headers.setBearerAuth(bearerToken);
        }
        return restTemplate.exchange(
            "/api/webhooks/alertmanager",
            HttpMethod.POST,
            new HttpEntity<>(Map.of("alerts", List.of()), headers),
            String.class
        );
    }

    private ClusterFixture createCluster() throws Exception {
        String token = adminToken();
        ResponseEntity<String> created = exchange(
            token,
            HttpMethod.POST,
            "/api/clusters",
            Map.of("name", "security-boundary-" + UUID.randomUUID(), "environment", "test")
        );
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode body = objectMapper.readTree(created.getBody());
        return new ClusterFixture(
            body.path("cluster_id").asText(),
            body.path("bootstrap_token").asText(),
            token
        );
    }

    private String issueManifestToken(ClusterFixture cluster) throws Exception {
        ResponseEntity<String> install = exchange(
            cluster.adminToken(),
            HttpMethod.GET,
            "/api/clusters/" + cluster.clusterId() + "/install-command?backend_url=https://rca.example.com",
            null
        );
        assertThat(install.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode commands = objectMapper.readTree(install.getBody()).path("commands");
        for (JsonNode command : commands) {
            String text = command.asText();
            if (text.contains("manifest_token=")) {
                String encoded = text.replaceAll(".*[?&]manifest_token=([^&\\\"]+).*", "$1");
                return URLDecoder.decode(encoded, StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError("install command did not contain manifest_token");
    }

    private String adminToken() throws Exception {
        ResponseEntity<String> login = restTemplate.postForEntity(
            "/api/auth/login",
            Map.of("username", "admin", "password", "admin"),
            String.class
        );
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(login.getBody()).path("access_token").asText();
    }

    private ResponseEntity<String> exchange(String token, HttpMethod method, String path, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return restTemplate.exchange(path, method, new HttpEntity<>(body, headers), String.class);
    }

    private HttpEntity<Object> jsonEntity(Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Forwarded-For", "203.0.113.20");
        headers.set("User-Agent", "SecurityBoundaryRegressionTests/1.0");
        return new HttpEntity<>(body, headers);
    }

    private long auditCount(String eventType) {
        Long count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_events WHERE event_type = ?",
            Long.class,
            eventType
        );
        return count == null ? 0 : count;
    }

    private String latestAuditDetails(String eventType) {
        return jdbc.query(
            "SELECT details_json FROM audit_events WHERE event_type = ? ORDER BY created_at DESC LIMIT 1",
            (rs, rowNum) -> rs.getString(1),
            eventType
        ).stream().findFirst().orElse("");
    }

    private record ClusterFixture(String clusterId, String bootstrapToken, String adminToken) {}
}
