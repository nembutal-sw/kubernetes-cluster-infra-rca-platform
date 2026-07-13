package io.clusterinfra.rca.webconsole;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.clusterinfra.rca.webconsole.analysis.AnalysisContext;
import io.clusterinfra.rca.webconsole.analysis.Signal;
import io.clusterinfra.rca.webconsole.analysis.detector.ApiServerLatencyDetector;
import io.clusterinfra.rca.webconsole.analysis.detector.CoreDnsHealthDetector;
import io.clusterinfra.rca.webconsole.analysis.detector.CniFailureDetector;
import io.clusterinfra.rca.webconsole.analysis.detector.ConntrackPressureDetector;
import io.clusterinfra.rca.webconsole.analysis.detector.DiskPressureDetector;
import io.clusterinfra.rca.webconsole.analysis.detector.DnsConfigurationDetector;
import io.clusterinfra.rca.webconsole.analysis.detector.DnsLatencyDetector;
import io.clusterinfra.rca.webconsole.analysis.detector.EtcdLatencyDetector;
import io.clusterinfra.rca.webconsole.analysis.detector.KernelLogDetector;
import io.clusterinfra.rca.webconsole.analysis.detector.KubeletFailureDetector;
import io.clusterinfra.rca.webconsole.analysis.detector.NodePressureConditionDetector;
import io.clusterinfra.rca.webconsole.analysis.detector.NodeReadinessDetector;
import io.clusterinfra.rca.webconsole.analysis.detector.PidPressureDetector;
import io.clusterinfra.rca.webconsole.analysis.detector.RuntimeFailureDetector;
import io.clusterinfra.rca.webconsole.config.RcaConsoleProperties;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SignalDetectorTests {
    private final RcaConsoleProperties properties = new RcaConsoleProperties();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void diskDetectorExplainsThresholdAndMatchedField() {
        AnalysisContext context = context(Map.of(
            "disk", Map.of(
                "root_usage_percent", 96.0,
                "secondary_usage_percent", 42.0
            )
        ));

        List<Signal> signals = new DiskPressureDetector().detect(context);

        assertThat(signals).extracting(Signal::name).contains("disk_usage_critical");
        Signal signal = signals.stream()
            .filter(item -> "disk_usage_critical".equals(item.name()))
            .findFirst()
            .orElseThrow();
        assertThat(signal.threshold()).isEqualTo(properties.getThresholds().getDiskCriticalPercent());
        assertThat(signal.matchedFields()).contains("disk.root_usage_percent");
        assertThat(signal.supportingEvidence().getFirst()).contains(">= threshold");
    }

    @Test
    void conntrackDetectorSeparatesFailuresDropsAndNearLimit() {
        AnalysisContext context = context(Map.of(
            "conntrack", Map.of(
                "count", 990,
                "max", 1000,
                "insert_failed", 6,
                "drop", 3,
                "early_drop", 2
            )
        ));

        assertThat(new ConntrackPressureDetector().detect(context))
            .extracting(Signal::name)
            .containsExactly(
                "conntrack_insert_failures",
                "conntrack_packet_drops",
                "conntrack_near_limit"
            );
    }

    @Test
    void conntrackDetectorDetectsKernelTableFullLog() {
        AnalysisContext context = context(Map.of(
            "kernel", Map.of("messages", List.of("nf_conntrack: table full, dropping packet"))
        ));

        assertThat(new ConntrackPressureDetector().detect(context))
            .extracting(Signal::name)
            .containsExactly("conntrack_table_full");
    }

    @Test
    void conntrackDetectorIgnoresUnrelatedInterfaceDrops() {
        AnalysisContext context = context(Map.of(
            "network", Map.of(
                "interface_rx_drop_total", 500,
                "interface_tx_drop_total", 300
            ),
            "conntrack", Map.of("count", 10, "max", 1000)
        ));

        assertThat(new ConntrackPressureDetector().detect(context)).isEmpty();
    }

    @Test
    void pidCriticalThresholdCanBeOverridden() {
        properties.getThresholds().setPidWarningPercent(80);
        properties.getThresholds().setPidCriticalPercent(90);
        AnalysisContext context = context(Map.of(
            "pid", Map.of("usage_percent", 91)
        ));

        Signal signal = new PidPressureDetector().detect(context).getFirst();

        assertThat(signal.name()).isEqualTo("pid_usage_high");
        assertThat(signal.severity()).isEqualTo("critical");
        assertThat(signal.threshold()).isEqualTo(90.0);
    }

    @Test
    void invalidThresholdValuesFallBackToSafeDefaults() {
        properties.getThresholds().setDiskWarningPercent(150);
        properties.getThresholds().setDiskCriticalPercent(10);
        AnalysisContext context = context(Map.of(
            "disk", Map.of("usage_percent", 91)
        ));

        Signal signal = new DiskPressureDetector().detect(context).getFirst();

        assertThat(signal.name()).isEqualTo("disk_usage_critical");
        assertThat(signal.threshold()).isEqualTo(90.0);
    }

    @Test
    void apiServerDetectorUsesStructuredControlPlaneFields() {
        AnalysisContext context = context(Map.of(
            "kubernetes", Map.of(
                "api_server_latency_ms", 1_600.0,
                "api_readyz_failed_check_count", 1,
                "api_livez_failed_check_count", 1,
                "api_request_error_count", 2,
                "api_timeout_detected", true
            ),
            "application", Map.of("api_server_latency_ms", 5_000.0)
        ));

        assertThat(new ApiServerLatencyDetector().detect(context))
            .extracting(Signal::name)
            .containsExactly(
                "api_server_latency_high",
                "api_server_readyz_failed",
                "api_server_livez_failed",
                "api_server_request_errors"
            );
    }

    @Test
    void apiServerDetectorIgnoresUnrelatedLatencyFields() {
        AnalysisContext context = context(Map.of(
            "application", Map.of("api_server_latency_ms", 5_000.0),
            "network", Map.of("request_latency_ms", 5_000.0)
        ));

        assertThat(new ApiServerLatencyDetector().detect(context)).isEmpty();
    }

    @Test
    void etcdDetectorSeparatesLatencyReadyzAndPodHealth() {
        AnalysisContext context = context(Map.of(
            "etcd", Map.of("fsync_latency_ms", 900.0),
            "kubernetes", Map.of(
                "etcd_readyz_healthy", false,
                "etcd_non_running_pods", List.of(Map.of(
                    "namespace", "kube-system",
                    "name", "etcd-control-a",
                    "phase", "CrashLoopBackOff",
                    "restart_count", 7
                ))
            )
        ));

        assertThat(new EtcdLatencyDetector().detect(context))
            .extracting(Signal::name)
            .containsExactly(
                "etcd_latency_high",
                "etcd_readyz_failed",
                "etcd_pod_unhealthy"
            );
    }

    @Test
    void unknownStatusDoesNotBecomeCriticalFailure() {
        AnalysisContext context = context(Map.of(
            "kubelet", Map.of("status", "unknown", "health_check", "not collected")
        ));

        assertThat(new KubeletFailureDetector().detect(context)).isEmpty();
    }

    @Test
    void rke2InventoryAbsenceAndOptionalCliProbeDoNotBecomeRuntimeFailures() {
        AnalysisContext context = context(Map.of(
            "systemd", Map.of(
                "collection_mode", "file",
                "units", Map.of(
                    "microk8s.daemon-kubelet", Map.of("unit_file_present", false),
                    "microk8s.daemon-containerd", Map.of("unit_file_present", false)
                )
            ),
            "runtime", Map.of(
                "status", "ok",
                "runtime_kind", "containerd",
                "runtime_socket_healthy", true,
                "containerd_socket_healthy", true,
                "ctr_version", Map.of("ok", false, "stderr", "command not found: ctr")
            ),
            "kubelet", Map.of(
                "status", "ok",
                "collection_mode", "file",
                "journal", Map.of("ok", false, "skipped", true)
            ),
            "kubernetes", Map.of(
                "node_ready", true,
                "container_runtime_version", "containerd://2.1.4-k3s2"
            )
        ));

        assertThat(new KubeletFailureDetector().detect(context)).isEmpty();
        assertThat(new RuntimeFailureDetector().detect(context)).isEmpty();
    }

    @Test
    void runtimeDetectorStillReportsExplicitSocketFailure() {
        AnalysisContext context = context(Map.of(
            "runtime", Map.of(
                "status", "failed",
                "runtime_socket_healthy", false,
                "containerd_socket_healthy", false
            )
        ));

        assertThat(new RuntimeFailureDetector().detect(context))
            .extracting(Signal::name)
            .contains("container_runtime_unit_unhealthy", "containerd_unit_unhealthy");
    }

    @Test
    void unrelatedErrorTextDoesNotBecomeKernelIoSignal() {
        AnalysisContext context = context(Map.of(
            "application", Map.of(
                "message", "request error rate is 1 percent",
                "io_error_detected", true
            ),
            "kernel", Map.of(
                "messages", List.of("device initialized successfully"),
                "io_error_detected", false,
                "read_only_filesystem_detected", false
            ),
            "disk", Map.of("kernel_io_error_detected", false)
        ));

        assertThat(new KernelLogDetector().detect(context)).isEmpty();
    }

    @Test
    void kernelDetectorUsesStructuredBooleanEvidence() {
        AnalysisContext context = context(Map.of(
            "kernel", Map.of("io_error_detected", true, "kernel_log_excerpt", List.of())
        ));

        assertThat(new KernelLogDetector().detect(context))
            .extracting(Signal::name)
            .containsExactly("kernel_io_error");
    }

    @Test
    void nodeReadinessIgnoresHealthyPressureFalseConditions() {
        AnalysisContext context = context(Map.of(
            "kubernetes", Map.of(
                "node_ready", true,
                "node_conditions", Map.of(
                    "MemoryPressure", Map.of("status", "False"),
                    "DiskPressure", Map.of("status", "False"),
                    "PIDPressure", Map.of("status", "False")
                )
            )
        ));

        assertThat(new NodeReadinessDetector().detect(context)).isEmpty();
    }

    @Test
    void nodeReadinessDetectsReadyFalseOnly() {
        AnalysisContext context = context(Map.of(
            "kubernetes", Map.of(
                "node_ready", false,
                "node_conditions", Map.of(
                    "MemoryPressure", Map.of("status", "False"),
                    "Ready", Map.of("status", "False")
                )
            )
        ));

        assertThat(new NodeReadinessDetector().detect(context))
            .extracting(Signal::name)
            .containsExactly("node_not_ready");
    }

    @Test
    void nodePressureConditionDetectorMapsKubernetesPressureConditionsToSubsystems() {
        AnalysisContext context = context(Map.of(
            "kubernetes", Map.of(
                "node_conditions", Map.of(
                    "DiskPressure", Map.of("status", "True"),
                    "MemoryPressure", Map.of("status", true),
                    "PIDPressure", Map.of("status", "true"),
                    "NetworkUnavailable", Map.of("status", "TRUE"),
                    "Ready", Map.of("status", "False")
                )
            )
        ));

        List<Signal> signals = new NodePressureConditionDetector().detect(context);

        assertThat(signals)
            .extracting(Signal::name)
            .containsExactlyInAnyOrder(
                "node_disk_pressure",
                "node_memory_pressure",
                "node_pid_pressure",
                "node_network_unavailable"
            );
        assertThat(signals)
            .extracting(Signal::component)
            .contains("disk", "memory", "process", "network");
    }

    @Test
    void nodePressureConditionDetectorIgnoresFalseHealthyConditions() {
        AnalysisContext context = context(Map.of(
            "kubernetes", Map.of(
                "node_conditions", Map.of(
                    "DiskPressure", Map.of("status", "False"),
                    "MemoryPressure", Map.of("status", false),
                    "PIDPressure", Map.of("status", "Unknown"),
                    "NetworkUnavailable", Map.of("status", "False")
                )
            )
        ));

        assertThat(new NodePressureConditionDetector().detect(context)).isEmpty();
    }

    @Test
    void corednsDetectorDetectsMissingReadyEndpoints() {
        AnalysisContext context = context(Map.of(
            "kubernetes", Map.of(
                "endpoint_slices", Map.of(
                    "ok", true,
                    "data", Map.of("items", List.of(Map.of(
                        "metadata", Map.of(
                            "name", "kube-dns-q5dn2",
                            "labels", Map.of("kubernetes.io/service-name", "kube-dns")
                        ),
                        "endpoints", List.of()
                    )))
                )
            )
        ));

        assertThat(new CoreDnsHealthDetector().detect(context))
            .extracting(Signal::name)
            .containsExactly("coredns_no_ready_endpoints");
    }

    @Test
    void corednsDetectorUsesAgentSummaryFieldsOnEveryNode() {
        AnalysisContext context = context(Map.of(
            "kubernetes", Map.of(
                "coredns_service_observed", true,
                "coredns_endpoint_count", 0,
                "coredns_ready_endpoint_count", 0
            )
        ));

        assertThat(new CoreDnsHealthDetector().detect(context))
            .extracting(Signal::name)
            .containsExactly("coredns_no_ready_endpoints");
    }

    @Test
    void dnsDetectorsIgnoreCorednsEndpointSummaryFields() {
        AnalysisContext context = context(Map.of(
            "kubernetes", Map.of(
                "coredns_service_observed", true,
                "coredns_has_ready_endpoints", false,
                "coredns_pods", Map.of("latency_ms", 900.0)
            )
        ));

        assertThat(new DnsConfigurationDetector().detect(context)).isEmpty();
        assertThat(new DnsLatencyDetector().detect(context)).isEmpty();
    }

    @Test
    void dnsDetectorsUseResolverSpecificFields() {
        AnalysisContext context = context(Map.of(
            "dns", Map.of(
                "dns_configured", false,
                "dns_lookup_latency_ms", 900.0
            )
        ));

        assertThat(new DnsConfigurationDetector().detect(context))
            .extracting(Signal::name)
            .containsExactly("dns_unconfigured");
        assertThat(new DnsLatencyDetector().detect(context))
            .extracting(Signal::name)
            .containsExactly("dns_latency_high");
    }

    @Test
    void cniDetectorDetectsDaemonSetNotScheduled() {
        AnalysisContext context = context(Map.of(
            "kubernetes", Map.of(
                "cni_daemonsets_not_scheduled", List.of(Map.of(
                    "namespace", "kube-system",
                    "name", "kindnet",
                    "desired_number_scheduled", 0
                ))
            )
        ));

        assertThat(new CniFailureDetector().detect(context))
            .extracting(Signal::name)
            .containsExactly("cni_daemonset_not_scheduled");
    }

    @Test
    void cniDetectorDetectsDaemonSetUnavailable() {
        AnalysisContext context = context(Map.of(
            "kubernetes", Map.of(
                "cni_daemonsets_unavailable", List.of(Map.of(
                    "namespace", "kube-system",
                    "name", "kindnet",
                    "desired_number_scheduled", 2,
                    "number_ready", 1
                ))
            )
        ));

        assertThat(new CniFailureDetector().detect(context))
            .extracting(Signal::name)
            .containsExactly("cni_daemonset_unavailable");
    }

    @Test
    void cniDetectorDoesNotTreatHealthyFalseCountersAsConfigFailure() {
        AnalysisContext context = context(Map.of(
            "kubernetes", Map.of(
                "cni_daemonset_not_scheduled_count", 0,
                "cni_daemonset_unavailable_count", 0
            ),
            "cni", Map.of(
                "config_dir_exists", true,
                "parse_errors", List.of(),
                "plugin_errors_detected", false
            )
        ));

        assertThat(new CniFailureDetector().detect(context)).isEmpty();
    }

    private AnalysisContext context(Map<String, Object> collectors) {
        return AnalysisContext.create(collectors, properties.getThresholds(), objectMapper);
    }
}
