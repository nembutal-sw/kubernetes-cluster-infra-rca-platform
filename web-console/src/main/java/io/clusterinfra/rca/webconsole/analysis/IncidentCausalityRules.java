package io.clusterinfra.rca.webconsole.analysis;

import io.clusterinfra.rca.webconsole.domain.RcaModels.Incident;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RcaReport;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class IncidentCausalityRules {
    private static final Map<String, String> ALERT_FAMILIES = Map.ofEntries(
        Map.entry("diskpressure", "storage"),
        Map.entry("memorypressure", "memory"),
        Map.entry("oomkilldetected", "memory"),
        Map.entry("pidpressure", "process"),
        Map.entry("containerddown", "runtime"),
        Map.entry("containerruntimeunhealthy", "runtime"),
        Map.entry("kubeletdown", "kubelet"),
        Map.entry("kubeletunhealthy", "kubelet"),
        Map.entry("nodenotready", "node"),
        Map.entry("networkunavailable", "network"),
        Map.entry("cniunhealthy", "cni"),
        Map.entry("cnifailure", "cni"),
        Map.entry("corednsunhealthy", "dns"),
        Map.entry("corednslatencyhigh", "dns"),
        Map.entry("etcdlatencyhigh", "etcd"),
        Map.entry("apiserverlatencyhigh", "api_server")
    );
    private static final Map<String, Integer> ROOT_RANK = Map.ofEntries(
        Map.entry("storage", 0),
        Map.entry("memory", 0),
        Map.entry("process", 0),
        Map.entry("conntrack", 0),
        Map.entry("etcd", 0),
        Map.entry("runtime", 1),
        Map.entry("network", 1),
        Map.entry("systemd", 1),
        Map.entry("kubelet", 2),
        Map.entry("cni", 2),
        Map.entry("dns", 2),
        Map.entry("api_server", 2),
        Map.entry("node", 3),
        Map.entry("kubernetes", 4)
    );
    private static final List<Rule> RULES = List.of(
        rule("storage_runtime", "storage", "runtime", "storage degradation affected the container runtime", 0.86),
        rule("storage_kubelet", "storage", "kubelet", "storage pressure propagated to kubelet", 0.92),
        rule("storage_node", "storage", "node", "storage pressure propagated to node readiness", 0.94),
        rule("storage_etcd", "storage", "etcd", "storage latency propagated to etcd", 0.88),
        rule("storage_api_server", "storage", "api_server", "storage latency propagated to the API server", 0.74),
        rule("runtime_kubelet", "runtime", "kubelet", "runtime failure disrupted kubelet integration", 0.88),
        rule("runtime_node", "runtime", "node", "runtime failure propagated to node readiness", 0.86),
        rule("memory_runtime", "memory", "runtime", "memory pressure degraded the container runtime", 0.72),
        rule("memory_kubelet", "memory", "kubelet", "memory pressure degraded kubelet", 0.80),
        rule("memory_node", "memory", "node", "memory pressure propagated to node readiness", 0.88),
        rule("process_kubelet", "process", "kubelet", "PID pressure degraded kubelet", 0.80),
        rule("process_node", "process", "node", "PID pressure propagated to node readiness", 0.88),
        rule("conntrack_network", "conntrack", "network", "conntrack exhaustion degraded node networking", 0.90),
        rule("conntrack_cni", "conntrack", "cni", "conntrack exhaustion propagated to CNI traffic", 0.94),
        rule("conntrack_dns", "conntrack", "dns", "conntrack exhaustion propagated to DNS traffic", 0.96),
        rule("network_cni", "network", "cni", "network path degradation propagated to CNI", 0.88),
        rule("network_dns", "network", "dns", "network path degradation propagated to DNS", 0.88),
        rule("network_api_server", "network", "api_server", "network degradation increased API server latency", 0.86),
        rule("network_node", "network", "node", "network degradation propagated to node readiness", 0.84),
        rule("cni_dns", "cni", "dns", "CNI degradation propagated to DNS", 0.80),
        rule("cni_node", "cni", "node", "CNI degradation propagated to node availability", 0.88),
        rule("kubelet_node", "kubelet", "node", "kubelet failure propagated to node readiness", 0.97),
        rule("systemd_runtime", "systemd", "runtime", "systemd unit failure degraded the container runtime", 0.86),
        rule("systemd_kubelet", "systemd", "kubelet", "systemd unit failure degraded kubelet", 0.88),
        rule("systemd_node", "systemd", "node", "systemd unit restart loop propagated to node readiness", 0.82),
        rule("etcd_api_server", "etcd", "api_server", "etcd latency propagated to the API server", 0.98)
    );
    private static final List<SpecificRule> SPECIFIC_RULES = List.of(
        specific("disk_io_to_kubelet", "disk_io_latency_high", "kubelet_unit_unhealthy",
            "block-device latency stalled kubelet operations", 0.96),
        specific("disk_pressure_to_kubelet", "disk_usage_critical", "kubelet_unit_unhealthy",
            "filesystem pressure degraded kubelet operations", 0.91),
        specific("inode_pressure_to_kubelet", "inode_usage_critical", "kubelet_unit_unhealthy",
            "inode exhaustion degraded kubelet operations", 0.91),
        specific("kernel_io_to_kubelet", "kernel_io_error", "kubelet_unit_unhealthy",
            "kernel I/O errors degraded kubelet operations", 0.94),
        specific("kubelet_to_node_ready", "kubelet_unit_unhealthy", "node_not_ready",
            "kubelet failure caused the node readiness loss", 0.98),
        specific("runtime_to_kubelet", "container_runtime_unit_unhealthy", "kubelet_unit_unhealthy",
            "container runtime failure disrupted kubelet CRI integration", 0.93),
        specific("containerd_to_kubelet", "containerd_unit_unhealthy", "kubelet_unit_unhealthy",
            "containerd failure disrupted kubelet CRI integration", 0.94),
        specific("runtime_to_node_ready", "container_runtime_unit_unhealthy", "node_not_ready",
            "container runtime failure propagated to node readiness", 0.89),
        specific("conntrack_to_dns_latency", "conntrack_near_limit", "dns_latency_high",
            "conntrack pressure increased DNS latency", 0.97),
        specific("conntrack_full_to_dns", "conntrack_table_full", "dns_latency_high",
            "full conntrack table dropped DNS traffic", 0.98),
        specific("conntrack_failures_to_coredns", "conntrack_insert_failures", "coredns_no_ready_endpoints",
            "conntrack insertion failures can make CoreDNS endpoints appear unreachable", 0.86),
        specific("dns_timeout_to_coredns", "ebpf_dns_timeout", "coredns_no_ready_endpoints",
            "eBPF DNS timeout events align with CoreDNS endpoint degradation", 0.82),
        specific("tcp_retransmit_to_dns_timeout", "ebpf_tcp_retransmit", "ebpf_dns_timeout",
            "TCP retransmissions preceded DNS request timeouts", 0.84),
        specific("cni_mtu_to_dns", "cni_mtu_values_inconsistent", "dns_latency_high",
            "CNI MTU mismatch can fragment or drop DNS traffic", 0.83),
        specific("cni_to_coredns", "cni_daemonset_unavailable", "coredns_no_ready_endpoints",
            "CNI DaemonSet unavailability can remove CoreDNS reachability", 0.88),
        specific("etcd_fsync_to_api_latency", "etcd_latency_high", "api_server_latency_high",
            "etcd latency increased API server request latency", 0.99),
        specific("etcd_readyz_to_api_readyz", "etcd_readyz_failed", "api_server_readyz_failed",
            "API server readiness failed because the etcd dependency was unhealthy", 0.99),
        specific("api_latency_to_node_ready", "api_server_latency_high", "node_not_ready",
            "API server latency can delay node status updates and readiness checks", 0.78),
        specific("memory_to_oom", "memory_pressure_critical", "kernel_oom_detected",
            "critical memory pressure led to kernel OOM activity", 0.96),
        specific("ebpf_oom_to_memory", "ebpf_oom_kill", "memory_pressure_critical",
            "eBPF observed an OOM kill during memory pressure", 0.92),
        specific("oom_to_node_ready", "kernel_oom_detected", "node_not_ready",
            "kernel OOM activity can destabilize node readiness", 0.82),
        specific("pid_to_kubelet", "pid_usage_high", "kubelet_unit_unhealthy",
            "PID exhaustion can prevent kubelet process/thread creation", 0.82),
        specific("pid_to_node_ready", "pid_usage_high", "node_not_ready",
            "PID pressure propagated to node readiness", 0.86),
        specific("systemd_to_kubelet", "systemd_failed_units", "kubelet_unit_unhealthy",
            "systemd reported kubelet failure or restart loop", 0.91),
        specific("systemd_to_runtime", "systemd_failed_units", "container_runtime_unit_unhealthy",
            "systemd reported runtime failure or restart loop", 0.86),
        specific("nic_flap_to_network", "nic_link_flap", "api_server_request_errors",
            "NIC link flap can interrupt API server requests from the node", 0.83)
    );

    public SignalProfile profile(RcaReport report) {
        String alertName = String.valueOf(report.trigger().getOrDefault(
            "alert_name",
            report.summary().symptom()
        ));
        Collection<String> components = strings(report.scope().get("components"));
        return profile(alertName, components, report.summary().mostLikelyCause());
    }

    public SignalProfile profile(Incident incident, RcaReport report) {
        if (report != null) {
            return profile(report);
        }
        return profile(incident.alertName(), List.of(), incident.rootCause());
    }

    public SignalProfile profile(String alertName, Collection<String> components, String cause) {
        LinkedHashSet<String> families = new LinkedHashSet<>();
        String alertFamily = ALERT_FAMILIES.get(normalize(alertName));
        if (alertFamily != null) {
            families.add(alertFamily);
        }
        if (components != null) {
            components.stream()
                .map(this::familyForComponent)
                .filter(value -> value != null && !value.isBlank())
                .forEach(families::add);
        }
        addCauseFamilies(families, cause);
        if (families.isEmpty()) {
            families.add("kubernetes");
        }
        String primary = families.stream()
            .min(Comparator.comparingInt(this::rootRank).thenComparing(value -> value))
            .orElse("kubernetes");
        return new SignalProfile(primary, Set.copyOf(families));
    }

    public String familyForEvent(String component, String eventType) {
        String alertFamily = ALERT_FAMILIES.get(normalize(eventType));
        if (alertFamily != null) {
            return alertFamily;
        }
        String eventFamily = familyForComponent(eventType);
        if (eventFamily != null) {
            return eventFamily;
        }
        String componentFamily = familyForComponent(component);
        return componentFamily == null ? "kubernetes" : componentFamily;
    }

    public String familyForComponent(String component) {
        String normalized = normalize(component);
        if (normalized.contains("inode") || normalized.contains("disk") || normalized.contains("filesystem")
            || normalized.contains("storage") || normalized.contains("block")
            || normalized.equals("io") || normalized.contains("io_") || normalized.contains("io ")) {
            return "storage";
        }
        if (normalized.contains("containerd") || normalized.contains("runtime")
            || normalized.equals("crio") || normalized.equals("docker")) {
            return "runtime";
        }
        if (normalized.contains("kubelet")) {
            return "kubelet";
        }
        if (normalized.contains("conntrack")) {
            return "conntrack";
        }
        if (normalized.contains("cni")) {
            return "cni";
        }
        if (normalized.contains("coredns") || normalized.contains("dns")) {
            return "dns";
        }
        if (normalized.contains("etcd")) {
            return "etcd";
        }
        if (normalized.contains("apiserver") || normalized.contains("api_server")
            || normalized.contains("api server")) {
            return "api_server";
        }
        if (normalized.contains("systemd") || normalized.contains("unit")) {
            return "systemd";
        }
        if (normalized.contains("memory") || normalized.contains("oom")) {
            return "memory";
        }
        if (normalized.contains("process") || normalized.contains("pid")) {
            return "process";
        }
        if (normalized.contains("network") || normalized.contains("nic") || normalized.contains("tcp")) {
            return "network";
        }
        if (normalized.contains("node")) {
            return "node";
        }
        return null;
    }

    public Optional<CausalRelation> relation(String sourceFamily, String targetFamily) {
        return relation(sourceFamily, targetFamily, null, null);
    }

    public Optional<CausalRelation> relation(
        String sourceFamily,
        String targetFamily,
        String sourceEventType,
        String targetEventType
    ) {
        if (sourceFamily == null || targetFamily == null) {
            return Optional.empty();
        }
        Optional<CausalRelation> specific = specificRelation(sourceEventType, targetEventType);
        if (specific.isPresent()) {
            return specific;
        }
        if (sourceFamily.equals(targetFamily)) {
            return Optional.of(new CausalRelation(
                "same_" + sourceFamily,
                "same subsystem signal continued",
                0.99
            ));
        }
        return RULES.stream()
            .filter(rule -> rule.sourceFamily().equals(sourceFamily)
                && rule.targetFamily().equals(targetFamily))
            .map(rule -> new CausalRelation(rule.ruleId(), rule.relationship(), rule.confidence()))
            .findFirst();
    }

    private Optional<CausalRelation> specificRelation(String sourceEventType, String targetEventType) {
        String source = normalize(sourceEventType).replace(' ', '_');
        String target = normalize(targetEventType).replace(' ', '_');
        if (source.isBlank() || target.isBlank() || source.equals(target)) {
            return Optional.empty();
        }
        return SPECIFIC_RULES.stream()
            .filter(rule -> rule.sourceEventType().equals(source)
                && rule.targetEventType().equals(target))
            .map(rule -> new CausalRelation(rule.ruleId(), rule.relationship(), rule.confidence()))
            .findFirst();
    }

    public Optional<CausalRelation> bestRelation(SignalProfile source, SignalProfile target) {
        CausalRelation best = null;
        for (String sourceFamily : source.families()) {
            for (String targetFamily : target.families()) {
                Optional<CausalRelation> candidate = relation(sourceFamily, targetFamily);
                if (candidate.isPresent()
                    && (best == null || candidate.get().confidence() > best.confidence())) {
                    best = candidate.get();
                }
            }
        }
        return Optional.ofNullable(best);
    }

    public boolean connected(String family, Set<String> incidentFamilies) {
        if (family == null) {
            return false;
        }
        if (incidentFamilies.contains(family)) {
            return true;
        }
        for (String incidentFamily : incidentFamilies) {
            if (relation(family, incidentFamily).isPresent()
                || relation(incidentFamily, family).isPresent()) {
                return true;
            }
        }
        return false;
    }

    public int rootRank(String family) {
        return ROOT_RANK.getOrDefault(family, 5);
    }

    private void addCauseFamilies(Set<String> families, String cause) {
        String normalized = normalize(cause);
        List.of(
            "storage", "disk", "inode", "filesystem", "runtime", "containerd", "kubelet",
            "conntrack", "network", "cni", "dns", "etcd", "api server", "apiserver",
            "memory", "oom", "process", "pid"
        ).stream().filter(normalized::contains).forEach(token ->
            families.add(familyForComponent(token))
        );
    }

    private Collection<String> strings(Object value) {
        if (!(value instanceof Collection<?> collection)) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        collection.forEach(item -> values.add(String.valueOf(item)));
        return values;
    }

    private String normalize(String value) {
        return value == null
            ? ""
            : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]+", " ").trim();
    }

    private static Rule rule(
        String ruleId,
        String sourceFamily,
        String targetFamily,
        String relationship,
        double confidence
    ) {
        return new Rule(ruleId, sourceFamily, targetFamily, relationship, confidence);
    }

    private static SpecificRule specific(
        String ruleId,
        String sourceEventType,
        String targetEventType,
        String relationship,
        double confidence
    ) {
        return new SpecificRule(ruleId, sourceEventType, targetEventType, relationship, confidence);
    }

    public record SignalProfile(String primaryFamily, Set<String> families) {
    }

    public record CausalRelation(String ruleId, String relationship, double confidence) {
    }

    private record Rule(
        String ruleId,
        String sourceFamily,
        String targetFamily,
        String relationship,
        double confidence
    ) {
    }

    private record SpecificRule(
        String ruleId,
        String sourceEventType,
        String targetEventType,
        String relationship,
        double confidence
    ) {
    }
}
