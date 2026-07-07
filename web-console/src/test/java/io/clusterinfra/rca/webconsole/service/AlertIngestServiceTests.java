package io.clusterinfra.rca.webconsole.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.clusterinfra.rca.webconsole.config.RcaConsoleProperties;
import io.clusterinfra.rca.webconsole.domain.RcaModels.AgentStatus;
import io.clusterinfra.rca.webconsole.domain.RcaModels.AlertmanagerAlert;
import io.clusterinfra.rca.webconsole.domain.RcaModels.AlertmanagerPayload;
import io.clusterinfra.rca.webconsole.domain.RcaModels.AnalysisTask;
import io.clusterinfra.rca.webconsole.domain.RcaModels.AnalysisTaskStatus;
import io.clusterinfra.rca.webconsole.domain.RcaModels.Cluster;
import io.clusterinfra.rca.webconsole.domain.RcaModels.ClusterStatus;
import io.clusterinfra.rca.webconsole.domain.RcaModels.EvidenceBundle;
import io.clusterinfra.rca.webconsole.domain.RcaModels.EvidenceRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.EvidenceRequestCreateRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.EvidenceRequestStatus;
import io.clusterinfra.rca.webconsole.domain.RcaModels.NodeAgent;
import io.clusterinfra.rca.webconsole.domain.RcaModels.WebhookIngestResponse;
import io.clusterinfra.rca.webconsole.persistence.AgentRepository;
import io.clusterinfra.rca.webconsole.persistence.ClusterRepository;
import io.clusterinfra.rca.webconsole.persistence.EvidenceRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AlertIngestServiceTests {
    private static final String CLUSTER_ID = "cluster-1";
    private static final String NODE_NAME = "worker-a";
    private static final Instant STARTED_AT = Instant.parse("2026-07-06T01:00:00Z");
    private static final Instant ENDED_AT = Instant.parse("2026-07-06T01:05:00Z");

    @Mock
    private ClusterRepository clusters;

    @Mock
    private AgentRepository agents;

    @Mock
    private EvidenceRepository evidence;

    @Mock
    private IncidentLifecycleService lifecycle;

    @Mock
    private RcaMetrics metrics;

    private RcaConsoleProperties properties;
    private AlertIngestService service;

    @BeforeEach
    void setUp() {
        properties = new RcaConsoleProperties();
        properties.getPipeline().setMaxAttempts(5);
        service = new AlertIngestService(
            clusters,
            agents,
            evidence,
            lifecycle,
            properties,
            metrics,
            new CollectorSelectionService()
        );
    }

    @Test
    void firingAlertWithRegisteredAgentCreatesEvidenceRequest() {
        when(clusters.find(CLUSTER_ID)).thenReturn(Optional.of(cluster()));
        when(agents.find(CLUSTER_ID, NODE_NAME)).thenReturn(Optional.of(agent()));
        when(evidence.createRequest(any())).thenReturn(request());

        WebhookIngestResponse response = service.ingestAlertmanager(payload(alert(
            "firing",
            Map.of("alertname", "DiskPressure", "cluster_id", CLUSTER_ID, "node", NODE_NAME)
        )));

        assertThat(response.receivedAlerts()).isEqualTo(1);
        assertThat(response.createdEvidenceRequests()).hasSize(1);
        assertThat(response.queuedAnalysisTasks()).isEmpty();
        assertThat(response.skippedAlerts()).isEmpty();

        ArgumentCaptor<EvidenceRequestCreateRequest> request = ArgumentCaptor.forClass(
            EvidenceRequestCreateRequest.class
        );
        verify(evidence).createRequest(request.capture());
        assertThat(request.getValue().alertName()).isEqualTo("DiskPressure");
        assertThat(request.getValue().requestedCollectors()).contains("disk", "inode", "kernel");
        assertThat(request.getValue().timeRange())
            .containsEntry("from", STARTED_AT.toString())
            .doesNotContainKey("to");
        assertThat(request.getValue().context()).containsKeys("labels", "annotations", "generator_url");
        verify(metrics).evidenceRequest("alertmanager", "created", 1);
        verify(metrics).webhookIngest("accepted", 1, 1);
    }

    @Test
    void firingAlertWithoutRegisteredAgentQueuesAlertOnlyAnalysisTask() {
        when(clusters.find(CLUSTER_ID)).thenReturn(Optional.of(cluster()));
        when(agents.find(CLUSTER_ID, NODE_NAME)).thenReturn(Optional.empty());
        when(evidence.saveAndEnqueue(any(), eq("alertmanager"), eq(false), eq(5))).thenReturn(task());

        WebhookIngestResponse response = service.ingestAlertmanager(payload(alert(
            "firing",
            Map.of("alertname", "NodeNotReady", "cluster_id", CLUSTER_ID, "node", NODE_NAME)
        )));

        assertThat(response.createdEvidenceRequests()).isEmpty();
        assertThat(response.queuedAnalysisTasks()).hasSize(1);
        assertThat(response.skippedAlerts()).isEmpty();

        ArgumentCaptor<EvidenceBundle> bundle = ArgumentCaptor.forClass(EvidenceBundle.class);
        verify(evidence).saveAndEnqueue(bundle.capture(), eq("alertmanager"), eq(false), eq(5));
        assertThat(bundle.getValue().clusterId()).isEqualTo(CLUSTER_ID);
        assertThat(bundle.getValue().nodeName()).isEqualTo(NODE_NAME);
        assertThat(bundle.getValue().alertName()).isEqualTo("NodeNotReady");
        assertThat(bundle.getValue().collectors()).containsKey("alertmanager");
        verify(metrics).webhookIngest("accepted", 1, 1);
    }

    @Test
    void resolvedAlertClosesMatchingSignalWithoutQueueingAnalysis() {
        when(clusters.find(CLUSTER_ID)).thenReturn(Optional.of(cluster()));

        WebhookIngestResponse response = service.ingestAlertmanager(payload(alert(
            "resolved",
            Map.of("alertname", "DiskPressure", "cluster_id", CLUSTER_ID, "node", NODE_NAME)
        )));

        assertThat(response.createdEvidenceRequests()).isEmpty();
        assertThat(response.queuedAnalysisTasks()).isEmpty();
        assertThat(response.skippedAlerts()).isEmpty();
        verify(lifecycle).resolveSignal(CLUSTER_ID, NODE_NAME, "DiskPressure", ENDED_AT, "alertmanager");
        verifyNoInteractions(agents, evidence);
        verify(metrics).webhookIngest("accepted", 1, 1);
    }

    @Test
    void missingClusterIdIsRejectedPerAlert() {
        WebhookIngestResponse response = service.ingestAlertmanager(payload(alert(
            "firing",
            Map.of("alertname", "DiskPressure", "node", NODE_NAME)
        )));

        assertThat(response.receivedAlerts()).isEqualTo(1);
        assertThat(response.skippedAlerts()).containsExactly("DiskPressure: cluster_id label is missing");
        verify(metrics).webhookIngest("rejected", 1, 1);
        verifyNoInteractions(clusters, agents, evidence, lifecycle);
    }

    private AlertmanagerPayload payload(AlertmanagerAlert alert) {
        return new AlertmanagerPayload(
            "test",
            null,
            List.of(alert),
            Map.of(),
            Map.of(),
            "https://alertmanager.example.com"
        );
    }

    private AlertmanagerAlert alert(String status, Map<String, String> labels) {
        return new AlertmanagerAlert(
            status,
            labels,
            Map.of("summary", "test alert"),
            STARTED_AT,
            "resolved".equals(status) ? ENDED_AT : null,
            "https://prometheus.example.com/graph"
        );
    }

    private Cluster cluster() {
        return new Cluster(
            CLUSTER_ID,
            "production",
            "prod",
            "",
            ClusterStatus.active,
            "",
            STARTED_AT,
            STARTED_AT
        );
    }

    private NodeAgent agent() {
        return new NodeAgent(
            "agent-1",
            CLUSTER_ID,
            NODE_NAME,
            "0.1.0",
            AgentStatus.healthy,
            List.of("disk", "inode", "kernel"),
            Map.of(),
            Map.of(),
            STARTED_AT,
            STARTED_AT
        );
    }

    private EvidenceRequest request() {
        return new EvidenceRequest(
            "request-1",
            CLUSTER_ID,
            NODE_NAME,
            "DiskPressure",
            List.of("disk", "inode", "kernel"),
            EvidenceRequestStatus.pending,
            Map.of(),
            "Alertmanager firing alert",
            Map.of(),
            null,
            null,
            STARTED_AT,
            null
        );
    }

    private AnalysisTask task() {
        return new AnalysisTask(
            "task-1",
            "evidence-1",
            CLUSTER_ID,
            NODE_NAME,
            "NodeNotReady",
            "alertmanager",
            false,
            AnalysisTaskStatus.queued,
            0,
            5,
            STARTED_AT,
            null,
            null,
            null,
            null,
            null,
            STARTED_AT,
            null,
            null
        );
    }
}
