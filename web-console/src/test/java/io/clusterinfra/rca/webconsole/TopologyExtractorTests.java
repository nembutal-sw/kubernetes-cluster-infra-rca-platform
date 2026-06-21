package io.clusterinfra.rca.webconsole;

import static org.assertj.core.api.Assertions.assertThat;

import io.clusterinfra.rca.webconsole.analysis.TopologyExtractor;
import io.clusterinfra.rca.webconsole.domain.RcaModels.EvidenceBundle;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TopologyExtractorTests {
    private final TopologyExtractor extractor = new TopologyExtractor();

    @Test
    void extractsNodeWorkloadServiceAndEndpointRelationships() {
        var observation = extractor.extract(new EvidenceBundle(
            "evidence-topology",
            "cluster-1",
            "worker-a",
            "ScheduledCollection",
            Instant.parse("2026-06-21T08:00:00Z"),
            Map.of("kubernetes", Map.of(
                "node_name", "worker-a",
                "topology_inventory_collected", true,
                "topology_inventory_truncated", false,
                "nodes", response(List.of(Map.of(
                    "metadata", Map.of(
                        "name", "worker-a",
                        "labels", Map.of("node-role.kubernetes.io/worker", "")
                    ),
                    "status", Map.of("conditions", List.of(Map.of(
                        "type", "Ready",
                        "status", "True"
                    )))
                ))),
                "pods", response(List.of(Map.of(
                    "metadata", Map.of(
                        "namespace", "payments",
                        "name", "payment-api-abc",
                        "labels", Map.of("app", "payment-api"),
                        "ownerReferences", List.of(Map.of(
                            "kind", "ReplicaSet",
                            "name", "payment-api-7d9f9c"
                        ))
                    ),
                    "spec", Map.of("nodeName", "worker-a"),
                    "status", Map.of("phase", "Running")
                ))),
                "services", response(List.of(Map.of(
                    "metadata", Map.of(
                        "namespace", "payments",
                        "name", "payment-api"
                    ),
                    "spec", Map.of(
                        "selector", Map.of("app", "payment-api"),
                        "type", "ClusterIP"
                    )
                ))),
                "endpoint_slices", response(List.of(Map.of(
                    "metadata", Map.of(
                        "namespace", "payments",
                        "labels", Map.of("kubernetes.io/service-name", "payment-api")
                    ),
                    "endpoints", List.of(Map.of(
                        "nodeName", "worker-a",
                        "targetRef", Map.of(
                            "kind", "Pod",
                            "namespace", "payments",
                            "name", "payment-api-abc"
                        )
                    ))
                )))
            ))
        ));

        assertThat(observation.inventoryComplete()).isTrue();
        assertThat(observation.nodeInventoryCollected()).isTrue();
        assertThat(observation.podInventoryCollected()).isTrue();
        assertThat(observation.entities()).extracting(entity -> entity.id()).contains(
            "node:worker-a",
            "pod:payments/payment-api-abc",
            "replicaset:payments/payment-api-7d9f9c",
            "service:payments/payment-api"
        );
        assertThat(observation.relations()).extracting(relation -> relation.relationship()).contains(
            "hosts",
            "owns",
            "selects",
            "routes_to",
            "has_endpoint_on"
        );
    }

    @Test
    void returnsEmptyObservationWithoutKubernetesEvidence() {
        var observation = extractor.extract(new EvidenceBundle(
            "evidence-disk",
            "cluster-1",
            "worker-a",
            "DiskPressure",
            Instant.parse("2026-06-21T08:00:00Z"),
            Map.of("disk", Map.of("root_usage_percent", 96))
        ));

        assertThat(observation.entities()).isEmpty();
        assertThat(observation.relations()).isEmpty();
        assertThat(observation.inventoryComplete()).isFalse();
        assertThat(observation.nodeInventoryCollected()).isFalse();
        assertThat(observation.podInventoryCollected()).isFalse();
    }

    private Map<String, Object> response(List<Map<String, Object>> items) {
        return Map.of("ok", true, "data", Map.of("items", items));
    }
}
