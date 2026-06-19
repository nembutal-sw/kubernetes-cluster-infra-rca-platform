package io.clusterinfra.rca.webconsole;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PlatformHttpTests {
    private static final String PUBLIC_API_BASE_URL = "https://rca.example.com";
    private static String accessToken;
    private static String clusterId;
    private static String bootstrapToken;
    private static String nodeToken;
    private static String evidenceRequestId;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () ->
            "jdbc:h2:mem:platform-http-tests;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        registry.add("rca.public-api-base-url", () -> PUBLIC_API_BASE_URL);
        registry.add("rca.default-admin-username", () -> "admin");
        registry.add("rca.default-admin-password", () -> "admin");
        registry.add("spring.ai.model.chat", () -> "none");
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
            .contains("data-api-base=\"\"")
            .contains("data-public-api-base=\"" + PUBLIC_API_BASE_URL + "\"")
            .contains("/assets/console-app.js");
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
        nodeToken = objectMapper.readTree(registered.getBody()).path("node_token").asText();

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

        ResponseEntity<String> reports = exchange("/api/rca/reports", HttpMethod.GET, null);
        assertThat(reports.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode report = objectMapper.readTree(reports.getBody()).get(0);
        assertThat(report.path("summary").path("most_likely_cause").asText()).isNotBlank();
        assertThat(report.path("root_cause_candidates").size()).isGreaterThan(0);
        assertThat(report.path("recommended_actions").size()).isGreaterThan(0);
        assertThat(report.path("evidence").toString()).contains("derived_signals");
    }

    @Test
    @Order(5)
    void manifestRequiresUserOrBootstrapToken() {
        String endpoint = "/api/clusters/" + clusterId + "/agent-manifest?backend_url=https://rca.example.com";
        ResponseEntity<String> anonymous = restTemplate.getForEntity(endpoint, String.class);
        assertThat(anonymous.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        ResponseEntity<String> authorized = restTemplate.getForEntity(
            endpoint + "&agent_token=" + bootstrapToken,
            String.class
        );
        assertThat(authorized.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(authorized.getBody()).contains("\"kind\":\"DaemonSet\"");
    }

    private ResponseEntity<String> exchange(String path, HttpMethod method, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(path, method, new HttpEntity<>(body, headers), String.class);
    }
}
