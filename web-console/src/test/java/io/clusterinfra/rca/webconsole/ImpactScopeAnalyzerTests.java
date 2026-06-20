package io.clusterinfra.rca.webconsole;

import static org.assertj.core.api.Assertions.assertThat;

import io.clusterinfra.rca.webconsole.analysis.ImpactScopeAnalyzer;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ImpactScopeAnalyzerTests {
    private final ImpactScopeAnalyzer analyzer = new ImpactScopeAnalyzer();

    @Test
    void derivesPodsNamespacesAndOwnersFromKubernetesEvidence() {
        Map<String, Object> impact = analyzer.analyze(
            Map.of("kubernetes", Map.of(
                "pods", Map.of(
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
                        "spec", Map.of("nodeName", "worker-a"),
                        "status", Map.of("phase", "Running")
                    )))
                )
            )),
            "worker-a"
        );

        assertThat(strings(impact.get("affected_pods")))
            .containsExactly("payments/payment-api-7d9f9c");
        assertThat(strings(impact.get("affected_namespaces"))).containsExactly("payments");
        assertThat(strings(impact.get("affected_workloads")))
            .containsExactly("ReplicaSet/payment-api-7d9f9c");
        assertThat(strings(impact.get("affected_services"))).isEmpty();
    }

    @Test
    void doesNotInventImpactWhenKubernetesInventoryIsMissing() {
        Map<String, Object> impact = analyzer.analyze(
            Map.of("disk", Map.of("root_usage_percent", 96)),
            "worker-a"
        );

        assertThat(strings(impact.get("affected_pods"))).isEmpty();
        assertThat(impact.get("impact_assessment"))
            .isEqualTo("No workload inventory was available in the collected evidence.");
    }

    private List<String> strings(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(String::valueOf).toList();
    }
}
