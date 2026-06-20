package io.clusterinfra.rca.webconsole.service;

import io.clusterinfra.rca.webconsole.config.RcaConsoleProperties;
import io.clusterinfra.rca.webconsole.domain.RcaModels.AnalysisTask;
import io.clusterinfra.rca.webconsole.domain.RcaModels.Cluster;
import io.clusterinfra.rca.webconsole.domain.RcaModels.ClusterCreateRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.ClusterView;
import io.clusterinfra.rca.webconsole.domain.RcaModels.DemoScenario;
import io.clusterinfra.rca.webconsole.domain.RcaModels.DemoScenarioRunRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.DemoScenarioRunResponse;
import io.clusterinfra.rca.webconsole.domain.RcaModels.EvidenceBundle;
import io.clusterinfra.rca.webconsole.domain.RcaModels.UserAccount;
import io.clusterinfra.rca.webconsole.persistence.ClusterRepository;
import io.clusterinfra.rca.webconsole.persistence.EvidenceRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class DemoScenarioService {
    private static final List<DemoScenario> SCENARIOS = List.of(
        new DemoScenario("disk-pressure", "Disk Pressure", "DiskPressure",
            "Filesystem usage and block I/O latency exceed safe thresholds."),
        new DemoScenario("memory-pressure", "Memory Pressure", "MemoryPressure",
            "Node memory usage reaches a critical level."),
        new DemoScenario("kubelet-failure", "Kubelet Failure", "KubeletDown",
            "The kubelet service is inactive and cannot report node state."),
        new DemoScenario("runtime-failure", "Container Runtime Failure", "ContainerdDown",
            "Containerd health and service status checks fail."),
        new DemoScenario("coredns-latency", "CoreDNS Latency", "CoreDNSLatencyHigh",
            "DNS query latency exceeds the configured threshold."),
        new DemoScenario("cni-mtu-mismatch", "CNI MTU Mismatch", "NetworkUnavailable",
            "Host and overlay interface MTU values are inconsistent."),
        new DemoScenario("conntrack-exhaustion", "Conntrack Exhaustion", "NetworkUnavailable",
            "Conntrack occupancy approaches the kernel table limit."),
        new DemoScenario("etcd-latency", "Etcd Latency", "EtcdLatencyHigh",
            "Etcd request and fsync latency affect the control plane."),
        new DemoScenario("api-server-latency", "API Server Latency", "APIServerLatencyHigh",
            "Kubernetes API server response latency is elevated."),
        new DemoScenario("systemd-restart-loop", "Systemd Restart Loop", "NodeNotReady",
            "A critical systemd unit repeatedly enters auto-restart.")
    );

    private final RcaConsoleProperties properties;
    private final ClusterRepository clusters;
    private final EvidenceRepository evidence;
    private final AuditService audit;

    public DemoScenarioService(
        RcaConsoleProperties properties,
        ClusterRepository clusters,
        EvidenceRepository evidence,
        AuditService audit
    ) {
        this.properties = properties;
        this.clusters = clusters;
        this.evidence = evidence;
        this.audit = audit;
    }

    public boolean enabled() {
        return properties.getDemo().isEnabled();
    }

    public List<DemoScenario> scenarios() {
        return SCENARIOS;
    }

    public DemoScenarioRunResponse run(
        String scenarioKey,
        DemoScenarioRunRequest request,
        UserAccount user
    ) {
        if (!enabled()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "demo scenarios are disabled");
        }
        if (!request.confirmed()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "demo execution confirmation is required");
        }
        DemoScenario scenario = SCENARIOS.stream()
            .filter(item -> item.key().equals(scenarioKey))
            .findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "demo scenario not found"));
        Cluster cluster = resolveCluster(request.clusterId());
        Instant now = Instant.now();
        Map<String, Object> collectors = new LinkedHashMap<>(
            evidenceFor(scenario.key(), request.nodeNameOrDefault())
        );
        collectors.put("_meta", Map.of(
            "source", "demo",
            "demo", true,
            "scenario", scenario.key(),
            "generated_at", now.toString()
        ));
        AnalysisTask task = evidence.saveAndEnqueue(
            new EvidenceBundle(
                null,
                cluster.clusterId(),
                request.nodeNameOrDefault(),
                scenario.alertName(),
                now,
                Map.copyOf(collectors)
            ),
            "demo",
            false,
            properties.getPipeline().getMaxAttempts()
        );
        audit.user(
            user,
            "demo.scenario_queued",
            "analysis_task",
            task.taskId(),
            "queued",
            Map.of(
                "scenario", scenario.key(),
                "cluster_id", cluster.clusterId(),
                "node_name", request.nodeNameOrDefault(),
                "evidence_id", task.evidenceId()
            )
        );
        return new DemoScenarioRunResponse(scenario, ClusterView.from(cluster), task);
    }

    private Cluster resolveCluster(String clusterId) {
        if (clusterId != null && !clusterId.isBlank()) {
            return clusters.find(clusterId.trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "cluster not found"));
        }
        return clusters.list().stream()
            .filter(cluster -> "demo".equalsIgnoreCase(cluster.environment()))
            .findFirst()
            .orElseGet(() -> clusters.create(new ClusterCreateRequest(
                "Demo Cluster",
                "demo",
                "Generated by Demo Scenario Mode"
            )));
    }

    private Map<String, Object> evidenceFor(String scenarioKey, String nodeName) {
        return switch (scenarioKey) {
            case "disk-pressure" -> Map.of(
                "disk", Map.of("root_usage_percent", 96.0, "await_ms", 48.0),
                "inode", Map.of("root_usage_percent", 87.0),
                "kernel", Map.of("messages", List.of("EXT4-fs warning: delayed allocation failed")),
                "kubernetes", Map.of("pods", Map.of(
                    "ok", true,
                    "data", Map.of("items", List.of(Map.of(
                        "kind", "Pod",
                        "metadata", Map.of(
                            "namespace", "payments",
                            "name", "payment-api-7d9f9c",
                            "ownerReferences", List.of(Map.of(
                                "kind", "ReplicaSet",
                                "name", "payment-api-7d9f9c"
                            ))
                        ),
                        "spec", Map.of("nodeName", nodeName),
                        "status", Map.of("phase", "Running")
                    )))
                ))
            );
            case "memory-pressure" -> Map.of(
                "memory", Map.of("usage_percent", 94.0, "available_bytes", 268_435_456),
                "kernel", Map.of("messages", List.of("Memory cgroup out of memory: Killed process 4242"))
            );
            case "kubelet-failure" -> Map.of(
                "kubelet", Map.of("status", "failed", "ready", false),
                "systemd", Map.of("failed_units", List.of("kubelet.service"))
            );
            case "runtime-failure" -> Map.of(
                "runtime", Map.of("status", "unhealthy", "socket_ready", false),
                "containerd", Map.of("status", "failed"),
                "systemd", Map.of("failed_units", List.of("containerd.service"))
            );
            case "coredns-latency" -> Map.of(
                "dns", Map.of("latency_ms", 1_480.0, "timeouts", 37),
                "network", Map.of("packet_loss_percent", 3.5)
            );
            case "cni-mtu-mismatch" -> Map.of(
                "cni", Map.of("status", "unhealthy", "mtu_mismatch", true, "overlay_mtu", 1450),
                "network", Map.of("host_mtu", 1500, "message", "MTU mismatch detected")
            );
            case "conntrack-exhaustion" -> Map.of(
                "conntrack", Map.of("count", 64_800, "max", 65_536, "insert_failed", 182),
                "network", Map.of("drops", 241)
            );
            case "etcd-latency" -> Map.of(
                "etcd", Map.of("latency_ms", 1_240.0, "fsync_latency_ms", 83.0),
                "disk", Map.of("await_ms", 54.0)
            );
            case "api-server-latency" -> Map.of(
                "kubernetes", Map.of("api_server_latency_ms", 2_180.0, "ready", true),
                "network", Map.of("control_plane_rtt_ms", 420.0)
            );
            case "systemd-restart-loop" -> Map.of(
                "systemd", Map.of(
                    "failed_units", List.of("kubelet.service"),
                    "logs", List.of("kubelet.service: Start request repeated too quickly")
                ),
                "kubelet", Map.of("status", "failed")
            );
            default -> throw new ResponseStatusException(HttpStatus.NOT_FOUND, "demo scenario not found");
        };
    }
}
