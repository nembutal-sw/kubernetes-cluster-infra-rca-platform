package io.clusterinfra.rca.webconsole;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.clusterinfra.rca.webconsole.analysis.TopologyExtractor;
import io.clusterinfra.rca.webconsole.config.RcaConsoleProperties;
import io.clusterinfra.rca.webconsole.domain.RcaModels.TopologyEntity;
import io.clusterinfra.rca.webconsole.domain.RcaModels.TopologyObservation;
import io.clusterinfra.rca.webconsole.domain.RcaModels.TopologyRelation;
import io.clusterinfra.rca.webconsole.persistence.TopologyRepository;
import io.clusterinfra.rca.webconsole.service.TopologyService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TopologyServiceTests {
    private static final Instant OLDER = Instant.parse("2026-06-21T08:00:00Z");
    private static final Instant NEWER = Instant.parse("2026-06-21T08:05:00Z");

    @Test
    void replacesSuccessfulNodePodSnapshotAndRebuildsServiceSelectorRelations() {
        TopologyRepository repository = mock(TopologyRepository.class);
        TopologyService service = service(repository);
        TopologyObservation older = observation(
            "older",
            OLDER,
            List.of(
                node("worker-a"),
                pod("pod:payments/old", "old", "worker-a"),
                workload("replicaset:payments/old-rs", "old-rs"),
                service("service:payments/api", "api")
            ),
            List.of(
                relation("node:worker-a", "pod:payments/old", "hosts"),
                relation("replicaset:payments/old-rs", "pod:payments/old", "owns"),
                relation("service:payments/api", "pod:payments/old", "selects")
            ),
            true,
            true,
            true,
            true
        );
        TopologyObservation newer = observation(
            "newer",
            NEWER,
            List.of(
                node("worker-a"),
                pod("pod:payments/new", "new", "worker-a"),
                workload("replicaset:payments/new-rs", "new-rs")
            ),
            List.of(
                relation("node:worker-a", "pod:payments/new", "hosts"),
                relation("replicaset:payments/new-rs", "pod:payments/new", "owns")
            ),
            true,
            true,
            false,
            false
        );
        when(repository.listRange(
            eq("cluster-1"),
            any(Instant.class),
            any(Instant.class),
            eq(500)
        ))
            .thenReturn(List.of(newer, older));

        var topology = service.current("cluster-1");

        assertThat(topology.entities()).extracting(TopologyEntity::id)
            .contains("pod:payments/new", "replicaset:payments/new-rs", "service:payments/api")
            .doesNotContain("pod:payments/old", "replicaset:payments/old-rs");
        assertThat(topology.relations())
            .anySatisfy(relation -> {
                assertThat(relation.source()).isEqualTo("service:payments/api");
                assertThat(relation.target()).isEqualTo("pod:payments/new");
                assertThat(relation.relationship()).isEqualTo("selects");
            });
    }

    @Test
    void completeClusterInventoryExpiresMissingServicesButPartialInventoryDoesNot() {
        TopologyRepository repository = mock(TopologyRepository.class);
        TopologyService service = service(repository);
        TopologyObservation older = observation(
            "older",
            OLDER,
            List.of(node("worker-a"), service("service:payments/old", "old")),
            List.of(relation("service:payments/old", "node:worker-a", "has_endpoint_on")),
            true,
            false,
            true,
            true
        );
        TopologyObservation partial = observation(
            "partial",
            NEWER,
            List.of(service("service:payments/partial", "partial")),
            List.of(),
            false,
            false,
            true,
            false
        );
        when(repository.listRange(
            eq("cluster-1"),
            any(Instant.class),
            any(Instant.class),
            eq(500)
        ))
            .thenReturn(List.of(partial, older));

        var partialTopology = service.current("cluster-1");
        assertThat(partialTopology.services())
            .containsExactlyInAnyOrder("payments/old", "payments/partial");
        assertThat(partialTopology.inventoryComplete()).isFalse();

        TopologyObservation complete = observation(
            "complete",
            NEWER.plusSeconds(60),
            List.of(service("service:payments/new", "new")),
            List.of(),
            false,
            false,
            true,
            true
        );
        when(repository.listRange(
            eq("cluster-1"),
            any(Instant.class),
            any(Instant.class),
            eq(500)
        ))
            .thenReturn(List.of(complete, older));

        var completeTopology = service.current("cluster-1");
        assertThat(completeTopology.services()).containsExactly("payments/new");
        assertThat(completeTopology.inventoryComplete()).isTrue();
    }

    @Test
    void comparesEntityAndRelationshipChangesBetweenSnapshots() {
        TopologyRepository repository = mock(TopologyRepository.class);
        TopologyService service = service(repository);
        TopologyObservation older = observation(
            "older",
            OLDER,
            List.of(node("worker-a")),
            List.of(),
            true,
            false,
            false,
            false
        );
        TopologyObservation newer = observation(
            "newer",
            NEWER,
            List.of(node("worker-a"), service("service:payments/api", "api")),
            List.of(relation("service:payments/api", "node:worker-a", "has_endpoint_on")),
            false,
            false,
            true,
            true
        );
        when(repository.listRange(
            eq("cluster-1"),
            any(Instant.class),
            eq(OLDER),
            eq(500)
        )).thenReturn(List.of(older));
        when(repository.listRange(
            eq("cluster-1"),
            any(Instant.class),
            eq(NEWER),
            eq(500)
        )).thenReturn(List.of(newer, older));

        Map<String, Object> comparison = service.compare("cluster-1", OLDER, NEWER);

        assertThat(comparison.get("changed")).isEqualTo(true);
        assertThat(comparison.get("added_entity_ids"))
            .isEqualTo(List.of("service:payments/api"));
        assertThat((List<?>) comparison.get("added_relations")).hasSize(1);
    }

    private TopologyService service(TopologyRepository repository) {
        RcaConsoleProperties properties = new RcaConsoleProperties();
        properties.getTopology().setLookbackHours(24);
        properties.getTopology().setObservationLimit(500);
        return new TopologyService(mock(TopologyExtractor.class), repository, properties);
    }

    private TopologyObservation observation(
        String id,
        Instant observedAt,
        List<TopologyEntity> entities,
        List<TopologyRelation> relations,
        boolean nodeInventory,
        boolean podInventory,
        boolean inventoryCollected,
        boolean inventoryComplete
    ) {
        return new TopologyObservation(
            id,
            "cluster-1",
            "evidence-" + id,
            "worker-a",
            observedAt,
            entities,
            relations,
            nodeInventory,
            podInventory,
            inventoryCollected,
            inventoryComplete
        );
    }

    private TopologyEntity node(String name) {
        return new TopologyEntity(
            "node:" + name,
            "Node",
            "",
            name,
            name,
            List.of("worker"),
            Map.of(),
            Map.of("ready", true)
        );
    }

    private TopologyEntity pod(String id, String name, String nodeName) {
        return new TopologyEntity(
            id,
            "Pod",
            "payments",
            name,
            nodeName,
            List.of(),
            Map.of("app", "api"),
            Map.of("phase", "Running")
        );
    }

    private TopologyEntity workload(String id, String name) {
        return new TopologyEntity(
            id,
            "ReplicaSet",
            "payments",
            name,
            "",
            List.of(),
            Map.of(),
            Map.of()
        );
    }

    private TopologyEntity service(String id, String name) {
        return new TopologyEntity(
            id,
            "Service",
            "payments",
            name,
            "",
            List.of(),
            Map.of(),
            Map.of("selector", Map.of("app", "api"), "type", "ClusterIP")
        );
    }

    private TopologyRelation relation(String source, String target, String relationship) {
        return new TopologyRelation(source, target, relationship, 1.0, "test");
    }
}
