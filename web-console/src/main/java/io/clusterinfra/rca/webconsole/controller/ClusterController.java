package io.clusterinfra.rca.webconsole.controller;

import io.clusterinfra.rca.webconsole.config.RcaConsoleProperties;
import io.clusterinfra.rca.webconsole.domain.RcaModels.AgentStatus;
import io.clusterinfra.rca.webconsole.domain.RcaModels.AgentEnrollmentProfile;
import io.clusterinfra.rca.webconsole.domain.RcaModels.AgentEnrollmentProfileUpdateRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.Cluster;
import io.clusterinfra.rca.webconsole.domain.RcaModels.ClusterCollectionRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.ClusterCollectionResponse;
import io.clusterinfra.rca.webconsole.domain.RcaModels.ClusterCreateRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.ClusterThresholdSettings;
import io.clusterinfra.rca.webconsole.domain.RcaModels.ClusterThresholdUpdateRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.ClusterView;
import io.clusterinfra.rca.webconsole.domain.RcaModels.ClusterTopology;
import io.clusterinfra.rca.webconsole.domain.RcaModels.TopologyObservation;
import io.clusterinfra.rca.webconsole.domain.RcaModels.EvidenceRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.EvidenceRequestCreateRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.EvidenceRequestStatus;
import io.clusterinfra.rca.webconsole.domain.RcaModels.InstallCommandResponse;
import io.clusterinfra.rca.webconsole.domain.RcaModels.NodeAgent;
import io.clusterinfra.rca.webconsole.domain.RcaModels.UserAccount;
import io.clusterinfra.rca.webconsole.persistence.AgentRepository;
import io.clusterinfra.rca.webconsole.persistence.ClusterRepository;
import io.clusterinfra.rca.webconsole.persistence.EvidenceRepository;
import io.clusterinfra.rca.webconsole.security.AccessService;
import io.clusterinfra.rca.webconsole.service.AgentManifestService;
import io.clusterinfra.rca.webconsole.service.AgentEnrollmentService;
import io.clusterinfra.rca.webconsole.service.AgentManifestService.ManifestOptions;
import io.clusterinfra.rca.webconsole.service.CollectorSelectionService;
import io.clusterinfra.rca.webconsole.service.RcaMetrics;
import io.clusterinfra.rca.webconsole.service.AuditService;
import io.clusterinfra.rca.webconsole.service.ClusterThresholdService;
import io.clusterinfra.rca.webconsole.service.TopologyService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
    private final CollectorSelectionService collectorSelection;
    private final RcaConsoleProperties properties;
    private final AuditService audit;
    private final RcaMetrics metrics;
    private final TopologyService topology;
    private final ClusterThresholdService thresholds;
    private final AgentEnrollmentService enrollments;

    public ClusterController(
        ClusterRepository clusters,
        AgentRepository agents,
        EvidenceRepository evidence,
        AccessService access,
        AgentManifestService manifests,
        CollectorSelectionService collectorSelection,
        RcaConsoleProperties properties,
        AuditService audit,
        RcaMetrics metrics,
        TopologyService topology,
        ClusterThresholdService thresholds,
        AgentEnrollmentService enrollments
    ) {
        this.clusters = clusters;
        this.agents = agents;
        this.evidence = evidence;
        this.access = access;
        this.manifests = manifests;
        this.collectorSelection = collectorSelection;
        this.properties = properties;
        this.audit = audit;
        this.metrics = metrics;
        this.topology = topology;
        this.thresholds = thresholds;
        this.enrollments = enrollments;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public Cluster create(
        @Valid @RequestBody ClusterCreateRequest request,
        Authentication authentication,
        HttpServletRequest servletRequest
    ) {
        UserAccount user = access.currentUser(authentication);
        Cluster cluster = clusters.create(request);
        audit.user(
            user,
            "cluster.create",
            "cluster",
            cluster.clusterId(),
            "success",
            Map.of("name", cluster.name(), "environment", cluster.environment()),
            servletRequest
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

    @GetMapping("/{clusterId}/agent-enrollment")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','VIEWER')")
    public AgentEnrollmentProfile agentEnrollment(@PathVariable String clusterId) {
        return enrollments.profile(clusterId);
    }

    @PutMapping("/{clusterId}/agent-enrollment")
    @PreAuthorize("hasRole('ADMIN')")
    public AgentEnrollmentProfile updateAgentEnrollment(
        @PathVariable String clusterId,
        @Valid @RequestBody AgentEnrollmentProfileUpdateRequest request,
        Authentication authentication,
        HttpServletRequest servletRequest
    ) {
        UserAccount user = access.currentUser(authentication);
        AgentEnrollmentProfile profile = enrollments.update(clusterId, request);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("mode", profile.mode().name());
        details.put("bootstrap_fallback_allowed", profile.bootstrapFallbackAllowed());
        if (profile.configured()) {
            details.put("api_server_url", profile.apiServerUrl());
            details.put("ca_sha256", profile.caSha256());
            details.put("audience", profile.audience());
            details.put("namespace", profile.namespace());
            details.put("service_account", profile.serviceAccount());
            details.put("profile_version", profile.profileVersion());
            details.put("workload_identity_ready", profile.workloadIdentityReady());
            details.put("expected_service_account_uid", profile.expectedServiceAccountUid());
            details.put("expected_daemonset_name", profile.expectedDaemonSetName());
            details.put("expected_daemonset_uid", profile.expectedDaemonSetUid());
            details.put("allowed_image_digest", profile.allowedImageDigest());
            details.put(
                "legacy_unbound_token_grace_until",
                profile.legacyUnboundTokenGraceUntil()
            );
            details.put(
                "legacy_unbound_agent_count",
                profile.legacyUnboundAgents().size()
            );
        }
        audit.user(
            user,
            "cluster.agent_enrollment.update",
            "cluster",
            clusterId,
            "success",
            details,
            servletRequest
        );
        return profile;
    }

    @GetMapping("/{clusterId}/thresholds")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','VIEWER','APPROVER')")
    public ClusterThresholdSettings thresholds(@PathVariable String clusterId) {
        requireCluster(clusterId);
        return thresholds.settings(clusterId);
    }

    @PutMapping("/{clusterId}/thresholds")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public ClusterThresholdSettings updateThresholds(
        @PathVariable String clusterId,
        @Valid @RequestBody ClusterThresholdUpdateRequest request,
        Authentication authentication,
        HttpServletRequest servletRequest
    ) {
        requireCluster(clusterId);
        UserAccount user = access.currentUser(authentication);
        ClusterThresholdSettings settings = thresholds.replace(clusterId, request, user.email());
        audit.user(
            user,
            "cluster.thresholds.update",
            "cluster",
            clusterId,
            "success",
            Map.of(
                "override_count", settings.overrides().size(),
                "keys", new ArrayList<>(settings.overrides().keySet())
            ),
            servletRequest
        );
        return settings;
    }

    @DeleteMapping("/{clusterId}/thresholds")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public ClusterThresholdSettings clearThresholds(
        @PathVariable String clusterId,
        Authentication authentication,
        HttpServletRequest servletRequest
    ) {
        requireCluster(clusterId);
        UserAccount user = access.currentUser(authentication);
        ClusterThresholdSettings settings = thresholds.clear(clusterId);
        audit.user(
            user,
            "cluster.thresholds.clear",
            "cluster",
            clusterId,
            "success",
            Map.of("override_count", 0),
            servletRequest
        );
        return settings;
    }

    @GetMapping("/{clusterId}/topology")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','VIEWER','APPROVER')")
    public ClusterTopology topology(@PathVariable String clusterId) {
        requireCluster(clusterId);
        return topology.current(clusterId);
    }

    @GetMapping("/{clusterId}/topology/history")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','VIEWER','APPROVER')")
    public List<TopologyObservation> topologyHistory(
        @PathVariable String clusterId,
        @RequestParam(required = false) Instant from,
        @RequestParam(required = false) Instant to,
        @RequestParam(defaultValue = "100") int limit
    ) {
        requireCluster(clusterId);
        if (limit < 1 || limit > 500) {
            throw new ResponseStatusException(BAD_REQUEST, "limit must be between 1 and 500");
        }
        if (from != null && to != null && from.isAfter(to)) {
            throw new ResponseStatusException(BAD_REQUEST, "from must not be after to");
        }
        return topology.history(clusterId, from, to, limit);
    }

    @GetMapping("/{clusterId}/topology/compare")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','VIEWER','APPROVER')")
    public Map<String, Object> compareTopology(
        @PathVariable String clusterId,
        @RequestParam(name = "baseline_at") Instant baselineAt,
        @RequestParam(name = "target_at") Instant targetAt
    ) {
        requireCluster(clusterId);
        if (baselineAt.isAfter(targetAt)) {
            throw new ResponseStatusException(BAD_REQUEST, "baseline_at must not be after target_at");
        }
        return topology.compare(clusterId, baselineAt, targetAt);
    }

    @DeleteMapping("/{clusterId}")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> delete(
        @PathVariable String clusterId,
        @RequestParam(name = "confirm_name") String confirmName,
        Authentication authentication,
        HttpServletRequest servletRequest
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
            Map.of("name", cluster.name()),
            servletRequest
        );
        return Map.of("deleted", true, "cluster_id", clusterId, "name", cluster.name());
    }

    @PostMapping("/{clusterId}/agent-token/rotate")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> rotateAgentToken(
        @PathVariable String clusterId,
        Authentication authentication,
        HttpServletRequest servletRequest
    ) {
        requireCluster(clusterId);
        UserAccount user = access.currentUser(authentication);
        Cluster cluster = clusters.rotateBootstrapToken(clusterId);
        Instant issuedAt = Instant.now();
        audit.user(
            user,
            "cluster.agent_token_rotated",
            "cluster",
            clusterId,
            "success",
            Map.of("requires_agent_secret_update", true),
            servletRequest
        );
        return Map.of(
            "cluster_id", clusterId,
            "agent_token", cluster.bootstrapToken(),
            "issued_at", issuedAt,
            "expires_at", issuedAt.plusSeconds(properties.getSecurity().getAgentBootstrapTokenTtlSeconds()),
            "note", "This registration-only token is shown once and expires automatically."
        );
    }

    @PostMapping("/{clusterId}/agent-token/revoke")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> revokeAgentToken(
        @PathVariable String clusterId,
        Authentication authentication,
        HttpServletRequest servletRequest
    ) {
        requireCluster(clusterId);
        UserAccount user = access.currentUser(authentication);
        clusters.revokeBootstrapToken(clusterId);
        audit.user(
            user,
            "cluster.agent_token_revoked",
            "cluster",
            clusterId,
            "success",
            Map.of("new_agent_registration_blocked", true),
            servletRequest
        );
        return Map.of("cluster_id", clusterId, "revoked", true, "revoked_at", Instant.now());
    }

    @PostMapping("/{clusterId}/agents/{nodeName}/token/revoke")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> revokeNodeToken(
        @PathVariable String clusterId,
        @PathVariable String nodeName,
        Authentication authentication,
        HttpServletRequest servletRequest
    ) {
        requireCluster(clusterId);
        UserAccount user = access.currentUser(authentication);
        if (!agents.revokeNodeToken(clusterId, nodeName)) {
            throw new ResponseStatusException(NOT_FOUND, "agent not found");
        }
        audit.user(
            user,
            "agent.node_token_revoked",
            "agent",
            clusterId + "/" + nodeName,
            "success",
            Map.of("cluster_id", clusterId, "node_name", nodeName),
            servletRequest
        );
        return Map.of(
            "cluster_id", clusterId,
            "node_name", nodeName,
            "revoked", true,
            "revoked_at", Instant.now()
        );
    }

    @GetMapping("/{clusterId}/install-command")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public InstallCommandResponse installCommand(
        @PathVariable String clusterId,
        @RequestParam(name = "backend_url", required = false) String backendUrl,
        @RequestParam(required = false) String image,
        @RequestParam(required = false) String namespace,
        Authentication authentication,
        HttpServletRequest servletRequest
    ) {
        UserAccount user = access.currentUser(authentication);
        Cluster cluster = requireCluster(clusterId);
        audit.user(
            user,
            "cluster.install_command_viewed",
            "cluster",
            clusterId,
            "success",
            Map.of(
                "backend_url_provided", backendUrl != null && !backendUrl.isBlank(),
                "image_provided", image != null && !image.isBlank(),
                "namespace_provided", namespace != null && !namespace.isBlank()
            ),
            servletRequest
        );
        return manifests.installCommand(
            cluster,
            backendUrl,
            image,
            namespace,
            user.email()
        );
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
        @RequestParam(name = "agent_mode", required = false) String agentMode
    ) {
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
            systemdCollectorMode,
            agentMode
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
        Authentication authentication,
        HttpServletRequest servletRequest
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
            ? collectorSelection.collectorsFor(request.alertNameOrDefault())
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
            metrics.evidenceRequest("manual", "created", 1);
        }
        audit.user(
            user,
            "collection.request",
            "cluster",
            clusterId,
            "success",
            Map.of("requested_nodes", targets.size(), "created_requests", created.size(), "skipped_nodes", skipped.size()),
            servletRequest
        );
        return new ClusterCollectionResponse(clusterId, targets, created, skipped);
    }

    @GetMapping("/{clusterId}/evidence-requests")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','VIEWER')")
    public List<EvidenceRequest> evidenceRequests(
        @PathVariable String clusterId,
        @RequestParam(name = "node_name", required = false) String nodeName,
        @RequestParam(required = false) EvidenceRequestStatus status,
        @RequestParam(required = false) Instant before,
        @RequestParam(defaultValue = "100") int limit
    ) {
        requireCluster(clusterId);
        if (limit < 1 || limit > 200) {
            throw new ResponseStatusException(BAD_REQUEST, "limit must be between 1 and 200");
        }
        return evidence.listRecentRequests(clusterId, nodeName, status, before, limit);
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
            agent.agentProtocolVersion(),
            AgentStatus.offline,
            agent.supportedCollectors(),
            agent.metadata(),
            agent.health(),
            agent.registeredAt(),
            agent.lastHeartbeatAt()
        );
    }
}
