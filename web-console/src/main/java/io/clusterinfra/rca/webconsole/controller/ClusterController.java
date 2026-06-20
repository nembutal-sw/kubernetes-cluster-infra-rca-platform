package io.clusterinfra.rca.webconsole.controller;

import io.clusterinfra.rca.webconsole.config.RcaConsoleProperties;
import io.clusterinfra.rca.webconsole.domain.RcaModels.AgentStatus;
import io.clusterinfra.rca.webconsole.domain.RcaModels.Cluster;
import io.clusterinfra.rca.webconsole.domain.RcaModels.ClusterCollectionRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.ClusterCollectionResponse;
import io.clusterinfra.rca.webconsole.domain.RcaModels.ClusterCreateRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.ClusterView;
import io.clusterinfra.rca.webconsole.domain.RcaModels.EvidenceRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.EvidenceRequestCreateRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.InstallCommandResponse;
import io.clusterinfra.rca.webconsole.domain.RcaModels.NodeAgent;
import io.clusterinfra.rca.webconsole.domain.RcaModels.UserAccount;
import io.clusterinfra.rca.webconsole.persistence.AgentRepository;
import io.clusterinfra.rca.webconsole.persistence.ClusterRepository;
import io.clusterinfra.rca.webconsole.persistence.EvidenceRepository;
import io.clusterinfra.rca.webconsole.security.AccessService;
import io.clusterinfra.rca.webconsole.service.AgentManifestService;
import io.clusterinfra.rca.webconsole.service.AgentManifestService.ManifestOptions;
import io.clusterinfra.rca.webconsole.service.RcaService;
import io.clusterinfra.rca.webconsole.service.AuditService;
import jakarta.validation.Valid;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@RestController
@RequestMapping("/api/clusters")
public class ClusterController {
    private final ClusterRepository clusters;
    private final AgentRepository agents;
    private final EvidenceRepository evidence;
    private final AccessService access;
    private final AgentManifestService manifests;
    private final RcaService rcaService;
    private final RcaConsoleProperties properties;
    private final AuditService audit;

    public ClusterController(
        ClusterRepository clusters,
        AgentRepository agents,
        EvidenceRepository evidence,
        AccessService access,
        AgentManifestService manifests,
        RcaService rcaService,
        RcaConsoleProperties properties,
        AuditService audit
    ) {
        this.clusters = clusters;
        this.agents = agents;
        this.evidence = evidence;
        this.access = access;
        this.manifests = manifests;
        this.rcaService = rcaService;
        this.properties = properties;
        this.audit = audit;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public Cluster create(@Valid @RequestBody ClusterCreateRequest request, Authentication authentication) {
        UserAccount user = access.currentUser(authentication);
        Cluster cluster = clusters.create(request);
        audit.user(
            user,
            "cluster.create",
            "cluster",
            cluster.clusterId(),
            "success",
            Map.of("name", cluster.name(), "environment", cluster.environment())
        );
        return cluster;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','VIEWER')")
    public List<ClusterView> list() {
        return clusters.list().stream().map(ClusterView::from).toList();
    }

    @GetMapping("/{clusterId}")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','VIEWER')")
    public ClusterView get(@PathVariable String clusterId) {
        return ClusterView.from(requireCluster(clusterId));
    }

    @DeleteMapping("/{clusterId}")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> delete(
        @PathVariable String clusterId,
        @RequestParam(name = "confirm_name") String confirmName,
        Authentication authentication
    ) {
        UserAccount user = access.currentUser(authentication);
        Cluster cluster = requireCluster(clusterId);
        if (!cluster.name().equals(confirmName)) {
            throw new ResponseStatusException(BAD_REQUEST, "confirm_name must match the cluster name");
        }
        clusters.delete(clusterId);
        audit.user(
            user,
            "cluster.delete",
            "cluster",
            clusterId,
            "success",
            Map.of("name", cluster.name())
        );
        return Map.of("deleted", true, "cluster_id", clusterId, "name", cluster.name());
    }

    @GetMapping("/{clusterId}/install-command")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public InstallCommandResponse installCommand(
        @PathVariable String clusterId,
        @RequestParam(name = "backend_url", required = false) String backendUrl,
        @RequestParam(required = false) String image,
        @RequestParam(required = false) String namespace
    ) {
        return manifests.installCommand(requireCluster(clusterId), backendUrl, image, namespace);
    }

    @GetMapping("/{clusterId}/agent-manifest")
    public Map<String, Object> manifest(
        @PathVariable String clusterId,
        @RequestParam(name = "backend_url") String backendUrl,
        @RequestParam(required = false) String image,
        @RequestParam(required = false) String namespace,
        @RequestParam(name = "poll_interval_seconds", required = false) Integer pollIntervalSeconds,
        @RequestParam(name = "http_timeout_seconds", required = false) Integer httpTimeoutSeconds,
        @RequestParam(name = "command_timeout_seconds", required = false) Integer commandTimeoutSeconds,
        @RequestParam(name = "kubernetes_api_timeout_seconds", required = false) Integer kubernetesApiTimeoutSeconds,
        @RequestParam(name = "control_plane_probe_ports", required = false) String controlPlaneProbePorts,
        @RequestParam(name = "runtime_socket_paths", required = false) String runtimeSocketPaths,
        @RequestParam(name = "systemd_collector_mode", required = false) String systemdCollectorMode,
        @RequestParam(name = "agent_token", required = false) String agentToken,
        @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        Authentication authentication
    ) {
        access.verifyManifestAccess(clusterId, authorization, agentToken, authentication);
        Cluster cluster = requireCluster(clusterId);
        return manifests.manifest(cluster, new ManifestOptions(
            backendUrl,
            image,
            namespace,
            pollIntervalSeconds == null ? properties.getAgent().getPollIntervalSeconds() : pollIntervalSeconds,
            httpTimeoutSeconds == null ? properties.getAgent().getHttpTimeoutSeconds() : httpTimeoutSeconds,
            commandTimeoutSeconds == null ? properties.getAgent().getCommandTimeoutSeconds() : commandTimeoutSeconds,
            kubernetesApiTimeoutSeconds == null ? 5 : kubernetesApiTimeoutSeconds,
            controlPlaneProbePorts,
            runtimeSocketPaths,
            systemdCollectorMode
        ));
    }

    @GetMapping("/{clusterId}/agents")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','VIEWER')")
    public List<NodeAgent> agents(@PathVariable String clusterId) {
        requireCluster(clusterId);
        return agents.list(clusterId).stream().map(this::withFreshness).toList();
    }

    @GetMapping("/{clusterId}/agents/{nodeName}")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','VIEWER')")
    public NodeAgent agent(@PathVariable String clusterId, @PathVariable String nodeName) {
        requireCluster(clusterId);
        return agents.find(clusterId, nodeName)
            .map(this::withFreshness)
            .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "agent not found"));
    }

    @PostMapping("/{clusterId}/collection-runs")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public ClusterCollectionResponse collectionRun(
        @PathVariable String clusterId,
        @RequestBody ClusterCollectionRequest request,
        Authentication authentication
    ) {
        if (!request.confirmed()) {
            throw new ResponseStatusException(BAD_REQUEST, "collection confirmation is required");
        }
        Cluster cluster = requireCluster(clusterId);
        UserAccount user = access.currentUser(authentication);
        Map<String, NodeAgent> agents = new LinkedHashMap<>();
        this.agents.list(clusterId).forEach(agent -> agents.put(agent.nodeName(), agent));
        List<String> targets = request.nodeNamesOrEmpty().isEmpty()
            ? new ArrayList<>(agents.keySet())
            : new ArrayList<>(new LinkedHashSet<>(request.nodeNamesOrEmpty()));
        if (targets.isEmpty()) {
            throw new ResponseStatusException(CONFLICT, "cluster has no registered agents");
        }

        List<EvidenceRequest> created = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        List<String> collectors = request.collectorsOrEmpty().isEmpty()
            ? rcaService.collectorsFor(request.alertNameOrDefault())
            : request.collectorsOrEmpty();
        String requestedAt = Instant.now().toString();
        for (String nodeName : targets) {
            NodeAgent agent = agents.get(nodeName);
            if (agent == null) {
                skipped.add(nodeName + ": agent not registered");
                continue;
            }
            if (withFreshness(agent).status() == AgentStatus.offline) {
                skipped.add(nodeName + ": agent offline");
                continue;
            }
            Map<String, Object> context = new LinkedHashMap<>(request.contextOrEmpty());
            context.put("trigger", "backend_collection");
            context.put("requested_by", user.email());
            context.put("requested_at", requestedAt);
            created.add(evidence.createRequest(new EvidenceRequestCreateRequest(
                cluster.clusterId(),
                nodeName,
                request.alertNameOrDefault(),
                collectors,
                Map.of("source", "backend_collection", "requested_at", requestedAt),
                request.reasonOrDefault(),
                context
            )));
        }
        audit.user(
            user,
            "collection.request",
            "cluster",
            clusterId,
            "success",
            Map.of("requested_nodes", targets.size(), "created_requests", created.size(), "skipped_nodes", skipped.size())
        );
        return new ClusterCollectionResponse(clusterId, targets, created, skipped);
    }

    @GetMapping("/{clusterId}/evidence-requests")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','VIEWER')")
    public List<EvidenceRequest> evidenceRequests(@PathVariable String clusterId) {
        requireCluster(clusterId);
        return evidence.listRequests(clusterId, null, null, null);
    }

    private Cluster requireCluster(String clusterId) {
        return clusters.find(clusterId)
            .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "cluster not found"));
    }

    private NodeAgent withFreshness(NodeAgent agent) {
        Instant heartbeat = agent.lastHeartbeatAt();
        boolean offline = heartbeat == null || Duration.between(heartbeat, Instant.now()).getSeconds()
            > Math.max(1, properties.getAgentOfflineAfterSeconds());
        if (!offline) {
            return agent;
        }
        return new NodeAgent(
            agent.agentId(),
            agent.clusterId(),
            agent.nodeName(),
            agent.agentVersion(),
            AgentStatus.offline,
            agent.supportedCollectors(),
            agent.metadata(),
            agent.health(),
            agent.registeredAt(),
            agent.lastHeartbeatAt()
        );
    }
}
