package io.clusterinfra.rca.webconsole;

import static org.assertj.core.api.Assertions.assertThat;

import io.clusterinfra.rca.webconsole.domain.RcaModels.EvidenceBundle;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RcaReport;
import io.clusterinfra.rca.webconsole.service.RuleBasedRcaAnalyzer;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:rule-scenario-tests;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.ai.model.chat=none",
    "rca.llm.enabled=false"
})
class RuleBasedScenarioTests {
    @Autowired
    private RuleBasedRcaAnalyzer analyzer;

    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarios")
    void detectsPlannedInfrastructureFailureScenarios(
        String alertName,
        Map<String, Object> collectors,
        String expectedSignal
    ) {
        RcaReport report = analyzer.analyze(
            "report-scenario",
            new EvidenceBundle(
                "evidence-scenario",
                "cluster-scenario",
                "worker-a",
                alertName,
                Instant.now(),
                collectors
            )
        );

        assertThat(signalNames(report)).contains(expectedSignal);
        assertThat(report.rootCauseCandidates()).isNotEmpty();
        assertThat(report.recommendedActions()).isNotEmpty();
        assertThat(report.recommendedActions())
            .allSatisfy(action -> {
                if ("llm".equals(action.source())) {
                    assertThat(action.automationAllowed()).isFalse();
                }
            });
    }

    static Stream<Arguments> scenarios() {
        return Stream.of(
            Arguments.of(
                Named.of("NodeNotReady", "NodeNotReady"),
                Map.of("node", Map.of("ready", false, "status", "not_ready")),
                "node_not_ready"
            ),
            Arguments.of(
                Named.of("DiskPressure and inode exhaustion", "DiskPressure"),
                Map.of(
                    "disk", Map.of("root_usage_percent", 96.0, "await_ms", 45.0),
                    "inode", Map.of("inode_usage_percent", 98.0),
                    "kernel", Map.of("messages", List.of("EXT4-fs error: I/O error"))
                ),
                "disk_usage_critical"
            ),
            Arguments.of(
                Named.of("MemoryPressure and OOM", "MemoryPressure"),
                Map.of(
                    "memory", Map.of("usage_percent", 96.0),
                    "kernel", Map.of("messages", List.of("Out of memory: Killed process 200"))
                ),
                "memory_pressure_critical"
            ),
            Arguments.of(
                Named.of("PIDPressure", "PIDPressure"),
                Map.of("process", Map.of("pid_usage_percent", 96.0)),
                "pid_usage_high"
            ),
            Arguments.of(
                Named.of("NetworkUnavailable and conntrack exhaustion", "NetworkUnavailable"),
                Map.of(
                    "conntrack", Map.of("count", 990, "max", 1000),
                    "kernel", Map.of("messages", List.of("eth0: Link is Down"))
                ),
                "conntrack_near_limit"
            ),
            Arguments.of(
                Named.of("Kubelet failure", "KubeletUnhealthy"),
                Map.of("kubelet", Map.of("status", "failed", "active", false)),
                "kubelet_unit_unhealthy"
            ),
            Arguments.of(
                Named.of("Container runtime failure", "ContainerRuntimeUnhealthy"),
                Map.of("runtime", Map.of("status", "unhealthy", "socket_healthy", false)),
                "container_runtime_unit_unhealthy"
            ),
            Arguments.of(
                Named.of("CNI MTU mismatch", "NetworkUnavailable"),
                Map.of("cni", Map.of("mtu_mismatch", true, "configured", true)),
                "cni_mtu_values_inconsistent"
            ),
            Arguments.of(
                Named.of("CoreDNS latency", "CoreDNSLatencyHigh"),
                Map.of("dns", Map.of("latency_ms", 850.0, "configured", true)),
                "dns_latency_high"
            ),
            Arguments.of(
                Named.of("Etcd latency", "EtcdLatencyHigh"),
                Map.of("etcd", Map.of("latency_ms", 900.0)),
                "etcd_latency_high"
            ),
            Arguments.of(
                Named.of("API Server latency", "APIServerLatencyHigh"),
                Map.of("kubernetes", Map.of("api_server_latency_ms", 1600.0)),
                "api_server_latency_high"
            ),
            Arguments.of(
                Named.of("systemd restart loop", "NodeNotReady"),
                Map.of("systemd", Map.of("failed_units", List.of(
                    Map.of("unit", "kubelet.service", "status", "failed")
                ))),
                "systemd_failed_units"
            )
        );
    }

    @SuppressWarnings("unchecked")
    private List<String> signalNames(RcaReport report) {
        return report.evidence().stream()
            .filter(section -> "derived_signals".equals(section.get("type")))
            .findFirst()
            .map(section -> (List<Map<String, Object>>) section.get("signals"))
            .orElse(List.of())
            .stream()
            .map(signal -> String.valueOf(signal.get("signal")))
            .toList();
    }
}
