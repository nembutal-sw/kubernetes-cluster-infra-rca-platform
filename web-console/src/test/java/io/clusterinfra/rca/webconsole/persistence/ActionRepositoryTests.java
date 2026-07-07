package io.clusterinfra.rca.webconsole.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import io.clusterinfra.rca.webconsole.domain.RcaModels.ActionExecutionStatus;
import io.clusterinfra.rca.webconsole.domain.RcaModels.ActionPlan;
import io.clusterinfra.rca.webconsole.domain.RcaModels.ActionRequestStatus;
import io.clusterinfra.rca.webconsole.domain.RcaModels.PolicyLevel;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class ActionRepositoryTests {
    private JdbcTemplate jdbc;
    private ObjectMapper objectMapper;
    private ActionRepository actions;
    private Instant now;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
            "jdbc:h2:mem:action-repository-" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
            "sa",
            ""
        );
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        objectMapper = objectMapper();
        actions = new ActionRepository(jdbc, objectMapper);
        now = Instant.parse("2026-06-21T04:00:00Z");
        seedReport("report-1", "cluster-1", now);
    }

    @Test
    void createDecideAndCompleteManualActionRequest() {
        var request = actions.createRequest(
            "report-1",
            0,
            "collect_node_diagnostics",
            PolicyLevel.MANUAL_INVESTIGATION,
            "rule_based",
            ActionRequestStatus.pending_approval,
            "admin",
            "need evidence",
            null
        );

        assertThat(actions.findRequest(request.actionRequestId())).contains(request);
        var approved = actions.decide(
            request.actionRequestId(),
            ActionRequestStatus.approved_manual,
            "approver",
            "run manually"
        ).orElseThrow();
        assertThat(approved.status()).isEqualTo(ActionRequestStatus.approved_manual);
        assertThat(approved.reviewedBy()).isEqualTo("approver");

        var completed = actions.completeManual(request.actionRequestId()).orElseThrow();
        assertThat(completed.status()).isEqualTo(ActionRequestStatus.completed);
        assertThat(actions.listRequests("report-1"))
            .extracting(actionRequest -> actionRequest.actionRequestId())
            .contains(request.actionRequestId());
        assertThat(actions.decide(
            request.actionRequestId(),
            ActionRequestStatus.rejected,
            "approver",
            "too late"
        )).isEmpty();
    }

    @Test
    void readsActionExecutionJsonFields() throws Exception {
        var request = actions.createRequest(
            "report-1",
            0,
            "collect_node_diagnostics",
            PolicyLevel.MANUAL_INVESTIGATION,
            "rule_based",
            ActionRequestStatus.accepted,
            "admin",
            null,
            null
        );
        ActionPlan preview = new ActionPlan(
            "collect_node_diagnostics",
            Map.of("node", "worker-a"),
            List.of("kubectl describe node worker-a"),
            null,
            false,
            60
        );
        jdbc.update(
            """
                INSERT INTO action_executions
                    (execution_id, action_request_id, report_id, cluster_id, node_name, action_key,
                     command_key, parameters_json, preview_json, status, timeout_seconds, requested_by,
                     approved_by, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
            "execution-1",
            request.actionRequestId(),
            "report-1",
            "cluster-1",
            "worker-a",
            "collect_node_diagnostics",
            preview.commandKey(),
            "{\"node\":\"worker-a\"}",
            objectMapper.writeValueAsString(preview),
            ActionExecutionStatus.expired.name(),
            60,
            "admin",
            "approver",
            Timestamp.from(now)
        );

        var execution = actions.findExecution("execution-1").orElseThrow();
        assertThat(execution.parameters()).containsEntry("node", "worker-a");
        assertThat(execution.preview().commandKey()).isEqualTo("collect_node_diagnostics");
        assertThat(execution.status()).isEqualTo(ActionExecutionStatus.expired);
        assertThat(actions.listExecutions("report-1"))
            .extracting(actionExecution -> actionExecution.executionId())
            .contains("execution-1");
    }

    private void seedReport(String reportId, String clusterId, Instant createdAt) {
        jdbc.update(
            """
                INSERT INTO clusters
                    (cluster_id, name, environment, description, status, bootstrap_token, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
            clusterId,
            "Test Cluster",
            "test",
            null,
            "active",
            "",
            Timestamp.from(createdAt)
        );
        jdbc.update(
            """
                INSERT INTO rca_reports
                    (report_id, cluster_id, status, trigger_json, scope_json, summary_json,
                     evidence_json, root_cause_candidates_json, recommended_actions_json,
                     policy_decisions_json, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
            reportId,
            clusterId,
            "completed",
            "{}",
            "{}",
            "{\"symptom\":\"DiskPressure\",\"most_likely_cause\":\"test\",\"confidence\":\"high\"}",
            "[]",
            "[]",
            "[]",
            "[]",
            Timestamp.from(createdAt)
        );
    }

    private ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.findAndRegisterModules();
        mapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        return mapper;
    }
}
