package io.clusterinfra.rca.webconsole.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.clusterinfra.rca.webconsole.domain.RcaModels.ActionExecutionRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.ActionPlan;
import io.clusterinfra.rca.webconsole.domain.RcaModels.ActionRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.ActionRequestStatus;
import io.clusterinfra.rca.webconsole.domain.RcaModels.AgentStatus;
import io.clusterinfra.rca.webconsole.domain.RcaModels.Confidence;
import io.clusterinfra.rca.webconsole.domain.RcaModels.EvidenceRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.EvidenceRequestCreateRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.EvidenceRequestStatus;
import io.clusterinfra.rca.webconsole.domain.RcaModels.NodeAgent;
import io.clusterinfra.rca.webconsole.domain.RcaModels.PolicyLevel;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RcaJobStatus;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RcaReport;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RcaSummary;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RecommendedAction;
import io.clusterinfra.rca.webconsole.domain.RcaModels.UserAccount;
import io.clusterinfra.rca.webconsole.domain.RcaModels.UserRole;
import io.clusterinfra.rca.webconsole.domain.RcaModels.UserStatus;
import io.clusterinfra.rca.webconsole.persistence.ActionRepository;
import io.clusterinfra.rca.webconsole.persistence.AgentRepository;
import io.clusterinfra.rca.webconsole.persistence.EvidenceRepository;
import io.clusterinfra.rca.webconsole.persistence.ReportRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;

class ActionWorkflowServiceTests {
    private static final Instant NOW = Instant.parse("2026-06-21T04:00:00Z");
    private static final UserAccount USER = new UserAccount(
        "user-1",
        "admin@example.com",
        "Admin",
        UserRole.admin,
        UserRole.admin,
        UserStatus.active,
        null,
        null,
        null,
        NOW,
        NOW
    );

    private ReportRepository reports;
    private ActionRepository actions;
    private AgentRepository agents;
    private EvidenceRepository evidence;
    private ActionWorkflowService service;
    private AtomicInteger actionRequestSequence;

    @BeforeEach
    void setUp() {
        reports = mock(ReportRepository.class);
        actions = mock(ActionRepository.class);
        agents = mock(AgentRepository.class);
        evidence = mock(EvidenceRepository.class);
        actionRequestSequence = new AtomicInteger();
        service = new ActionWorkflowService(
            reports,
            actions,
            agents,
            evidence,
            mock(AuditService.class),
            new RcaMetrics(new SimpleMeterRegistry())
        );
        stubCreateRequest();
        when(actions.findRequest(anyString())).thenReturn(Optional.empty());
    }

    @Test
    void llmActionIsRecordedAsBlockedAndDoesNotCreateEvidenceRequest() {
        RecommendedAction action = action(
            "restart_kubelet",
            PolicyLevel.APPROVAL_REQUIRED,
            "llm",
            false,
            true,
            true,
            List.of("llm_output_cannot_trigger_direct_automation")
        );
        when(reports.findReport("report-1")).thenReturn(Optional.of(report(Map.of("node_name", "worker-a"), action)));

        var response = service.execute(
            "report-1",
            0,
            new ActionExecutionRequest(true, "operator reviewed"),
            USER,
            new MockHttpServletRequest()
        );

        assertThat(response.status()).isEqualTo("blocked");
        assertThat(response.executionStarted()).isFalse();
        assertThat(response.message()).contains("LLM-origin actions");
        assertThat(response.actionRequest().status()).isEqualTo(ActionRequestStatus.blocked);
        verify(evidence, never()).createRequest(any());
    }

    @Test
    void ruleBasedApprovalRequiredActionCreatesPendingManualRequest() {
        RecommendedAction action = action(
            "restart_kubelet",
            PolicyLevel.APPROVAL_REQUIRED,
            "rule_based",
            false,
            true,
            true,
            List.of("mutation_requires_operator_approval")
        );
        when(reports.findReport("report-1")).thenReturn(Optional.of(report(Map.of("node_name", "worker-a"), action)));

        var response = service.execute(
            "report-1",
            0,
            new ActionExecutionRequest(true, "request approval"),
            USER,
            new MockHttpServletRequest()
        );

        assertThat(response.status()).isEqualTo("pending_approval");
        assertThat(response.requiresApproval()).isTrue();
        assertThat(response.message()).contains("Approval request recorded");
        assertThat(response.actionRequest().status()).isEqualTo(ActionRequestStatus.pending_approval);
        verify(evidence, never()).createRequest(any());
    }

    @Test
    void readOnlyActionUsesNodeNameScopeAndCreatesEvidenceRequest() {
        RecommendedAction action = action(
            "inspect_storage_state",
            PolicyLevel.AUTO_SAFE,
            "rule_based",
            true,
            false,
            false,
            List.of("read_only_evidence_collection")
        );
        when(reports.findReport("report-1")).thenReturn(Optional.of(report(Map.of("node_name", "worker-a"), action)));
        when(agents.find("cluster-1", "worker-a")).thenReturn(Optional.of(agent()));
        when(evidence.createRequest(any(EvidenceRequestCreateRequest.class))).thenAnswer(invocation -> {
            EvidenceRequestCreateRequest request = invocation.getArgument(0);
            return new EvidenceRequest(
                "evidence-request-1",
                request.clusterId(),
                request.nodeName(),
                request.alertName(),
                request.collectorsOrEmpty(),
                EvidenceRequestStatus.pending,
                request.timeRangeOrEmpty(),
                request.reason(),
                request.contextOrEmpty(),
                null,
                null,
                NOW,
                null
            );
        });

        var response = service.execute(
            "report-1",
            0,
            new ActionExecutionRequest(true, "collect only"),
            USER,
            new MockHttpServletRequest()
        );

        ArgumentCaptor<EvidenceRequestCreateRequest> evidenceRequest =
            ArgumentCaptor.forClass(EvidenceRequestCreateRequest.class);
        verify(evidence).createRequest(evidenceRequest.capture());
        assertThat(evidenceRequest.getValue().nodeName()).isEqualTo("worker-a");
        assertThat(evidenceRequest.getValue().requestedCollectors())
            .contains("disk", "inode", "kernel", "systemd");
        assertThat(evidenceRequest.getValue().context())
            .containsEntry("report_id", "report-1")
            .containsEntry("action_key", "inspect_storage_state");
        assertThat(response.status()).isEqualTo("accepted");
        assertThat(response.executionStarted()).isTrue();
        assertThat(response.evidenceRequest().requestId()).isEqualTo("evidence-request-1");
        assertThat(response.actionRequest().status()).isEqualTo(ActionRequestStatus.accepted);
    }

    @Test
    void missingAgentBlocksAutomationAndHandlesNullGuardrails() {
        RecommendedAction action = action(
            "inspect_storage_state",
            PolicyLevel.AUTO_SAFE,
            "rule_based",
            true,
            false,
            false,
            null
        );
        when(reports.findReport("report-1")).thenReturn(Optional.of(report(Map.of("node_name", "worker-a"), action)));
        when(agents.find("cluster-1", "worker-a")).thenReturn(Optional.empty());

        var response = service.execute(
            "report-1",
            0,
            new ActionExecutionRequest(true, "collect only"),
            USER,
            new MockHttpServletRequest()
        );

        assertThat(response.status()).isEqualTo("blocked");
        assertThat(response.guardrails()).containsExactly("agent_not_registered");
        assertThat(response.actionRequest().status()).isEqualTo(ActionRequestStatus.blocked);
        verify(evidence, never()).createRequest(any());
    }

    private void stubCreateRequest() {
        when(actions.createRequest(
            anyString(),
            anyInt(),
            anyString(),
            any(PolicyLevel.class),
            any(),
            any(ActionRequestStatus.class),
            anyString(),
            any(),
            any()
        )).thenAnswer(invocation -> new ActionRequest(
            "action-request-" + actionRequestSequence.incrementAndGet(),
            invocation.getArgument(0),
            invocation.getArgument(1),
            invocation.getArgument(2),
            invocation.getArgument(3),
            invocation.getArgument(4),
            invocation.getArgument(5),
            invocation.getArgument(6),
            null,
            invocation.getArgument(7),
            null,
            invocation.getArgument(8),
            NOW,
            null
        ));
    }

    private RcaReport report(Map<String, Object> scope, RecommendedAction action) {
        return new RcaReport(
            "report-1",
            "cluster-1",
            null,
            RcaJobStatus.completed,
            Map.of("alert_name", "DiskPressure"),
            scope,
            new RcaSummary("DiskPressure", "Storage pressure", Confidence.high),
            List.of(),
            List.of(),
            List.of(action),
            List.of(action),
            NOW
        );
    }

    private RecommendedAction action(
        String actionKey,
        PolicyLevel policy,
        String source,
        boolean automationAllowed,
        boolean requiresApproval,
        boolean reviewRequired,
        List<String> guardrails
    ) {
        return new RecommendedAction(
            actionKey.replace('_', ' '),
            policy,
            "Test action",
            actionKey,
            source,
            automationAllowed ? "read_only" : "manual",
            automationAllowed,
            requiresApproval,
            reviewRequired,
            guardrails,
            List.of(),
            new ActionPlan(
                actionKey,
                Map.of("node", "worker-a"),
                List.of("kubectl describe node worker-a"),
                null,
                automationAllowed,
                60
            )
        );
    }

    private NodeAgent agent() {
        return new NodeAgent(
            "agent-1",
            "cluster-1",
            "worker-a",
            "0.1.0",
            AgentStatus.healthy,
            List.of("disk", "inode", "kernel", "systemd"),
            Map.of(),
            Map.of(),
            NOW,
            NOW
        );
    }
}
