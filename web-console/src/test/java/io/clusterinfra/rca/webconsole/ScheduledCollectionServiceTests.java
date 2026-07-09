package io.clusterinfra.rca.webconsole;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.clusterinfra.rca.webconsole.config.RcaConsoleProperties;
import io.clusterinfra.rca.webconsole.domain.RcaModels.AgentStatus;
import io.clusterinfra.rca.webconsole.domain.RcaModels.Cluster;
import io.clusterinfra.rca.webconsole.domain.RcaModels.ClusterStatus;
import io.clusterinfra.rca.webconsole.domain.RcaModels.EvidenceRequestCreateRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.NodeAgent;
import io.clusterinfra.rca.webconsole.persistence.AgentRepository;
import io.clusterinfra.rca.webconsole.persistence.ClusterRepository;
import io.clusterinfra.rca.webconsole.persistence.EvidenceRepository;
import io.clusterinfra.rca.webconsole.service.AgentHealthService;
import io.clusterinfra.rca.webconsole.service.RcaMetrics;
import io.clusterinfra.rca.webconsole.service.ScheduledCollectionService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ScheduledCollectionServiceTests {
    @Test
    void createsTargetedEvidenceRequestForDegradedCollector() {
        ClusterRepository clusters = mock(ClusterRepository.class);
        AgentRepository agents = mock(AgentRepository.class);
        EvidenceRepository evidence = mock(EvidenceRepository.class);
        RcaConsoleProperties properties = properties();
        ScheduledCollectionService service = service(clusters, agents, evidence, properties);
        Cluster cluster = cluster();
        NodeAgent agent = agent(
            "worker-1",
            AgentStatus.healthy,
            Map.of("collectors", Map.of("disk", "failed"))
        );
        when(clusters.list()).thenReturn(List.of(cluster));
        when(agents.list(cluster.clusterId())).thenReturn(List.of(agent));
        when(evidence.hasPendingRequest(cluster.clusterId(), agent.nodeName())).thenReturn(false);

        service.requestScheduledEvidence();

        ArgumentCaptor<EvidenceRequestCreateRequest> request = ArgumentCaptor.forClass(EvidenceRequestCreateRequest.class);
        verify(evidence).createRequest(request.capture());
        EvidenceRequestCreateRequest value = request.getValue();
        assertThat(value.alertName()).isEqualTo("AgentCollectorDegraded");
        assertThat(value.requestedCollectors()).contains("disk", "kubelet", "runtime", "network");
        assertThat(value.requestedCollectors()).contains("systemd", "kernel", "process");
        assertThat(value.context())
            .containsEntry("trigger", "scheduled_monitoring")
            .containsEntry("health_status", "collector_degraded")
            .containsEntry("reported_status", "healthy");
        assertThat(value.context().get("health_reasons").toString())
            .contains("degraded")
            .contains("failed");
    }

    @Test
    void healthyAgentUsesLightweightBaselineCollectors() {
        ClusterRepository clusters = mock(ClusterRepository.class);
        AgentRepository agents = mock(AgentRepository.class);
        EvidenceRepository evidence = mock(EvidenceRepository.class);
        RcaConsoleProperties properties = properties();
        ScheduledCollectionService service = service(clusters, agents, evidence, properties);
        Cluster cluster = cluster();
        NodeAgent agent = agent("worker-1", AgentStatus.healthy, Map.of());
        when(clusters.list()).thenReturn(List.of(cluster));
        when(agents.list(cluster.clusterId())).thenReturn(List.of(agent));
        when(evidence.hasPendingRequest(cluster.clusterId(), agent.nodeName())).thenReturn(false);

        service.requestScheduledEvidence();

        ArgumentCaptor<EvidenceRequestCreateRequest> request = ArgumentCaptor.forClass(EvidenceRequestCreateRequest.class);
        verify(evidence).createRequest(request.capture());
        EvidenceRequestCreateRequest value = request.getValue();
        assertThat(value.alertName()).isEqualTo("ScheduledNodeHealth");
        assertThat(value.requestedCollectors())
            .containsExactly("node", "kubernetes", "disk", "inode", "memory", "network", "conntrack", "runtime", "cni", "dns");
        assertThat(value.requestedCollectors()).doesNotContain("systemd", "kernel", "kubelet", "process");
    }

    @Test
    void skipsScheduledRequestWhenPendingRequestAlreadyExists() {
        ClusterRepository clusters = mock(ClusterRepository.class);
        AgentRepository agents = mock(AgentRepository.class);
        EvidenceRepository evidence = mock(EvidenceRepository.class);
        RcaConsoleProperties properties = properties();
        ScheduledCollectionService service = service(clusters, agents, evidence, properties);
        Cluster cluster = cluster();
        NodeAgent agent = agent("worker-1", AgentStatus.healthy, Map.of());
        when(clusters.list()).thenReturn(List.of(cluster));
        when(agents.list(cluster.clusterId())).thenReturn(List.of(agent));
        when(evidence.hasPendingRequest(cluster.clusterId(), agent.nodeName())).thenReturn(true);

        service.requestScheduledEvidence();

        verify(evidence, never()).createRequest(any());
    }

    @Test
    void skipsScheduledRequestWhenRecentSameStatusRequestExists() {
        ClusterRepository clusters = mock(ClusterRepository.class);
        AgentRepository agents = mock(AgentRepository.class);
        EvidenceRepository evidence = mock(EvidenceRepository.class);
        RcaConsoleProperties properties = properties();
        ScheduledCollectionService service = service(clusters, agents, evidence, properties);
        Cluster cluster = cluster();
        NodeAgent agent = agent("worker-1", AgentStatus.healthy, Map.of());
        when(clusters.list()).thenReturn(List.of(cluster));
        when(agents.list(cluster.clusterId())).thenReturn(List.of(agent));
        when(evidence.hasPendingRequest(cluster.clusterId(), agent.nodeName())).thenReturn(false);
        when(evidence.hasRecentRequest(anyString(), anyString(), anyList(), any())).thenReturn(true);

        service.requestScheduledEvidence();

        verify(evidence, never()).createRequest(any());
    }

    @Test
    void skipsHealthyAgentsWhenBaselineCollectionIsDisabled() {
        ClusterRepository clusters = mock(ClusterRepository.class);
        AgentRepository agents = mock(AgentRepository.class);
        EvidenceRepository evidence = mock(EvidenceRepository.class);
        RcaConsoleProperties properties = properties();
        properties.getMonitoring().setCollectHealthyAgents(false);
        ScheduledCollectionService service = service(clusters, agents, evidence, properties);
        Cluster cluster = cluster();
        NodeAgent agent = agent("worker-1", AgentStatus.healthy, Map.of());
        when(clusters.list()).thenReturn(List.of(cluster));
        when(agents.list(cluster.clusterId())).thenReturn(List.of(agent));

        service.requestScheduledEvidence();

        verify(evidence, never()).createRequest(any());
        verify(evidence, never()).hasPendingRequest(anyString(), anyString());
    }

    @Test
    void doesNothingWhenBackendMonitoringIsDisabled() {
        ClusterRepository clusters = mock(ClusterRepository.class);
        AgentRepository agents = mock(AgentRepository.class);
        EvidenceRepository evidence = mock(EvidenceRepository.class);
        RcaConsoleProperties properties = properties();
        properties.getMonitoring().setEnabled(false);
        ScheduledCollectionService service = service(clusters, agents, evidence, properties);

        service.requestScheduledEvidence();

        verify(clusters, never()).list();
        verify(evidence, never()).createRequest(any());
    }

    private ScheduledCollectionService service(
        ClusterRepository clusters,
        AgentRepository agents,
        EvidenceRepository evidence,
        RcaConsoleProperties properties
    ) {
        return new ScheduledCollectionService(
            clusters,
            agents,
            evidence,
            properties,
            new RcaMetrics(new SimpleMeterRegistry()),
            new AgentHealthService(properties)
        );
    }

    private RcaConsoleProperties properties() {
        RcaConsoleProperties properties = new RcaConsoleProperties();
        properties.getMonitoring().setEnabled(true);
        properties.setAgentOfflineAfterSeconds(300);
        return properties;
    }

    private Cluster cluster() {
        Instant now = Instant.now();
        return new Cluster(
            "cluster-1",
            "production",
            "prod",
            "production cluster",
            ClusterStatus.active,
            "bootstrap-token",
            now.minusSeconds(3600),
            now
        );
    }

    private NodeAgent agent(String nodeName, AgentStatus status, Map<String, Object> health) {
        Instant now = Instant.now();
        return new NodeAgent(
            "agent-" + nodeName,
            "cluster-1",
            nodeName,
            "0.1.0",
            "1",
            status,
            List.of("node", "disk", "kubelet", "runtime", "network"),
            Map.of(),
            health,
            now.minusSeconds(3600),
            now.minusSeconds(10)
        );
    }
}
