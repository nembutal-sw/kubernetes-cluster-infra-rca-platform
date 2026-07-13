package io.clusterinfra.rca.webconsole;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.clusterinfra.rca.webconsole.domain.RcaModels.UserRole;
import io.clusterinfra.rca.webconsole.domain.RcaModels.UserStatus;
import io.clusterinfra.rca.webconsole.security.TokenService;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.beans.factory.annotation.Autowired;
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
class RbacHttpAuthorizationTests {
    private static final String PASSWORD = "rbac-password";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TokenService tokens;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String databaseName = "rbac-http-tests-" + UUID.randomUUID();
        registry.add("spring.datasource.url", () ->
            "jdbc:h2:mem:" + databaseName + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        registry.add("spring.ai.model.chat", () -> "none");
        registry.add("rca.pipeline.initial-delay-ms", () -> "600000");
        registry.add("rca.webhook-token", () -> "rbac-webhook-token");
        registry.add("rca.observability.metrics-token", () -> "rbac-metrics-token");
        registry.add("rca.export.signature-secret", () -> "rbac-signing-secret");
        registry.add("rca.export.signature-key-id", () -> "rbac-key");
    }

    @BeforeEach
    void setUpUsers() {
        for (UserRole role : UserRole.values()) {
            seedUser(role);
        }
    }

    @Test
    void roleMatrixProtectsSensitiveOperationalApis() throws Exception {
        String admin = token(UserRole.admin);
        String operator = token(UserRole.operator);
        String viewer = token(UserRole.viewer);
        String auditor = token(UserRole.auditor);
        String approver = token(UserRole.approver);

        assertStatus(admin, HttpMethod.POST, "/api/clusters", Map.of(
            "name", "rbac-cluster",
            "environment", "test"
        ), HttpStatus.CREATED);
        assertStatus(operator, HttpMethod.POST, "/api/clusters", Map.of(
            "name", "operator-rbac-cluster",
            "environment", "test"
        ), HttpStatus.CREATED);
        assertStatus(viewer, HttpMethod.POST, "/api/clusters", Map.of(
            "name", "viewer-rbac-cluster",
            "environment", "test"
        ), HttpStatus.FORBIDDEN);

        assertStatus(viewer, HttpMethod.GET, "/api/platform/info", null, HttpStatus.OK);
        assertStatus(viewer, HttpMethod.GET, "/api/v1/catalog", null, HttpStatus.OK);
        assertStatus(auditor, HttpMethod.GET, "/api/v1/catalog", null, HttpStatus.OK);
        assertStatus(null, HttpMethod.GET, "/api/v1/catalog", null, HttpStatus.UNAUTHORIZED);
        Map<String, Object> catalogPreview = Map.of(
            "override_json", "{\"schema_version\":\"rca-catalog/v1\",\"version\":\"rbac-preview\"}",
            "reason", "rbac test"
        );
        assertStatus(admin, HttpMethod.POST, "/api/v1/catalog/preview", catalogPreview, HttpStatus.OK);
        assertStatus(operator, HttpMethod.POST, "/api/v1/catalog/preview", catalogPreview, HttpStatus.OK);
        assertStatus(viewer, HttpMethod.POST, "/api/v1/catalog/preview", catalogPreview, HttpStatus.FORBIDDEN);
        assertStatus(auditor, HttpMethod.POST, "/api/v1/catalog/preview", catalogPreview, HttpStatus.FORBIDDEN);
        assertStatus(approver, HttpMethod.POST, "/api/v1/catalog/preview", catalogPreview, HttpStatus.FORBIDDEN);
        ResponseEntity<String> draft = exchange(admin, HttpMethod.POST, "/api/v1/catalog/overrides/drafts", catalogPreview);
        assertThat(draft.getStatusCode()).isEqualTo(HttpStatus.OK);
        String draftId = objectMapper.readTree(draft.getBody()).path("draft_id").asText();
        assertStatus(auditor, HttpMethod.GET, "/api/v1/catalog/overrides/drafts", null, HttpStatus.OK);
        assertStatus(approver, HttpMethod.GET, "/api/v1/catalog/overrides/drafts/" + draftId, null, HttpStatus.OK);
        assertStatus(viewer, HttpMethod.GET, "/api/v1/catalog/overrides/drafts", null, HttpStatus.FORBIDDEN);
        assertStatus(approver, HttpMethod.POST, "/api/v1/catalog/overrides/drafts", catalogPreview, HttpStatus.FORBIDDEN);
        assertStatus(operator, HttpMethod.POST, "/api/v1/catalog/overrides/drafts/" + draftId + "/approve", Map.of(
            "confirmed", true,
            "note", "operator cannot approve"
        ), HttpStatus.FORBIDDEN);
        assertStatus(approver, HttpMethod.POST, "/api/v1/catalog/overrides/drafts/" + draftId + "/approve", Map.of(
            "confirmed", true,
            "note", "approver can approve"
        ), HttpStatus.OK);
        assertStatus(operator, HttpMethod.GET, "/api/v1/catalog/overrides/drafts/" + draftId + "/handoff", null, HttpStatus.OK);
        assertStatus(auditor, HttpMethod.GET, "/api/v1/catalog/overrides/drafts/" + draftId + "/handoff", null, HttpStatus.FORBIDDEN);
        assertStatus(admin, HttpMethod.POST, "/api/v1/catalog/overrides/drafts/" + draftId + "/gitops-changes", Map.of(
            "confirmed", true
        ), HttpStatus.SERVICE_UNAVAILABLE);
        assertStatus(viewer, HttpMethod.POST, "/api/v1/catalog/overrides/drafts/" + draftId + "/gitops-changes", Map.of(
            "confirmed", true
        ), HttpStatus.FORBIDDEN);
        assertStatus(viewer, HttpMethod.GET, "/api/v1/gitops/changes", null, HttpStatus.OK);
        assertStatus(approver, HttpMethod.GET, "/api/v1/gitops/changes", null, HttpStatus.OK);
        assertStatus(auditor, HttpMethod.GET, "/api/v1/gitops/changes", null, HttpStatus.OK);
        assertStatus(null, HttpMethod.GET, "/api/v1/gitops/changes", null, HttpStatus.UNAUTHORIZED);
        assertStatus(operator, HttpMethod.POST, "/api/v1/gitops/changes/change-missing/outcome", Map.of(
            "confirmed", true,
            "deployment_state", "in_progress"
        ), HttpStatus.NOT_FOUND);
        assertStatus(approver, HttpMethod.POST, "/api/v1/gitops/changes/change-missing/outcome", Map.of(
            "confirmed", true,
            "deployment_state", "in_progress"
        ), HttpStatus.FORBIDDEN);
        assertStatus(approver, HttpMethod.GET, "/api/rca/reports", null, HttpStatus.OK);
        assertStatus(viewer, HttpMethod.GET, "/api/rca/reports", null, HttpStatus.OK);

        assertStatus(viewer, HttpMethod.GET, "/api/rca/reports/export", null, HttpStatus.FORBIDDEN);
        assertStatus(approver, HttpMethod.GET, "/api/rca/reports/export", null, HttpStatus.FORBIDDEN);
        assertStatus(operator, HttpMethod.GET, "/api/rca/reports/export", null, HttpStatus.OK);

        assertStatus(viewer, HttpMethod.GET, "/api/rca/reports/report-missing/bundle", null, HttpStatus.FORBIDDEN);
        assertStatus(approver, HttpMethod.GET, "/api/rca/reports/report-missing/bundle", null, HttpStatus.FORBIDDEN);
        assertStatus(operator, HttpMethod.GET, "/api/rca/reports/report-missing/bundle", null, HttpStatus.NOT_FOUND);

        assertStatus(auditor, HttpMethod.GET, "/api/audit/events", null, HttpStatus.OK);
        assertStatus(operator, HttpMethod.GET, "/api/audit/events", null, HttpStatus.FORBIDDEN);
        assertStatus(viewer, HttpMethod.GET, "/api/audit/events", null, HttpStatus.FORBIDDEN);

        assertStatus(auditor, HttpMethod.GET, "/api/audit/events/export?format=json", null, HttpStatus.OK);
        assertStatus(approver, HttpMethod.GET, "/api/audit/events/export?format=json", null, HttpStatus.FORBIDDEN);

        assertStatus(viewer, HttpMethod.POST, "/api/rca/reports/report-missing/actions/0/execute", Map.of(
            "confirmed", true,
            "note", "viewer must not execute"
        ), HttpStatus.FORBIDDEN);
        assertStatus(approver, HttpMethod.POST, "/api/rca/reports/report-missing/actions/0/execute", Map.of(
            "confirmed", true,
            "note", "approver must not execute directly"
        ), HttpStatus.FORBIDDEN);
        assertStatus(operator, HttpMethod.POST, "/api/rca/reports/report-missing/actions/0/execute", Map.of(
            "confirmed", true,
            "note", "operator is authorized but report is absent"
        ), HttpStatus.NOT_FOUND);

        assertStatus(approver, HttpMethod.POST, "/api/rca/action-requests/request-missing/approve", Map.of(
            "confirmed", true,
            "note", "approval role is allowed"
        ), HttpStatus.NOT_FOUND);
        assertStatus(viewer, HttpMethod.POST, "/api/rca/action-requests/request-missing/approve", Map.of(
            "confirmed", true,
            "note", "viewer is not allowed"
        ), HttpStatus.FORBIDDEN);

        assertStatus(auditor, HttpMethod.GET, "/actuator/prometheus", null, HttpStatus.OK);
        assertStatus(null, HttpMethod.GET, "/actuator/prometheus", null, HttpStatus.UNAUTHORIZED);
    }

    @Test
    void customGuardedEndpointsRejectAnonymousOrMalformedRequestsBeforeControllerLogic() {
        assertStatus(null, HttpMethod.GET, "/api/clusters/cluster-missing/agent-manifest", null, HttpStatus.UNAUTHORIZED);
        assertStatus(null, HttpMethod.POST, "/api/webhooks/alertmanager", Map.of("alerts", java.util.List.of()),
            HttpStatus.UNAUTHORIZED);
        assertStatus(null, HttpMethod.POST, "/api/agents/heartbeat", Map.of(
            "cluster_id", "cluster-missing",
            "node_name", "worker-a"
        ), HttpStatus.UNAUTHORIZED);
    }

    private String token(UserRole role) throws Exception {
        ResponseEntity<String> response = restTemplate.postForEntity(
            "/api/auth/login",
            Map.of("username", loginId(role), "password", PASSWORD),
            String.class
        );
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(response.getBody()).path("access_token").asText();
    }

    private void assertStatus(
        String token,
        HttpMethod method,
        String path,
        Object body,
        HttpStatus expectedStatus
    ) {
        ResponseEntity<String> response = exchange(token, method, path, body);
        assertThat(response.getStatusCode())
            .as("%s %s response body: %s", method, path, response.getBody())
            .isEqualTo(expectedStatus);
    }

    private ResponseEntity<String> exchange(String token, HttpMethod method, String path, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return restTemplate.exchange(path, method, new HttpEntity<>(body, headers), String.class);
    }

    private void seedUser(UserRole role) {
        String userId = "user-rbac-" + role.name();
        Integer existing = jdbc.queryForObject(
            "SELECT COUNT(*) FROM user_accounts WHERE user_id = ?",
            Integer.class,
            userId
        );
        if (existing != null && existing > 0) {
            return;
        }
        Instant now = Instant.now();
        jdbc.update(
            """
                INSERT INTO user_accounts
                    (user_id, email, full_name, password_hash, requested_role, role, status, reason,
                     approval_note, approved_by, created_at, approved_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
            userId,
            loginId(role),
            "RBAC " + role.name(),
            tokens.hashPassword(PASSWORD),
            role.name(),
            role.name(),
            UserStatus.active.name(),
            null,
            null,
            "system",
            Timestamp.from(now),
            Timestamp.from(now)
        );
    }

    private String loginId(UserRole role) {
        return "rbac-" + role.name().toLowerCase();
    }
}
