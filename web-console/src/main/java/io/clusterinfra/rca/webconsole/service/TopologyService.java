package io.clusterinfra.rca.webconsole.service;

import io.clusterinfra.rca.webconsole.analysis.TopologyExtractor;
import io.clusterinfra.rca.webconsole.config.RcaConsoleProperties;
import io.clusterinfra.rca.webconsole.domain.RcaModels.ClusterTopology;
import io.clusterinfra.rca.webconsole.domain.RcaModels.EvidenceBundle;
import io.clusterinfra.rca.webconsole.domain.RcaModels.TopologyEntity;
import io.clusterinfra.rca.webconsole.domain.RcaModels.TopologyObservation;
import io.clusterinfra.rca.webconsole.domain.RcaModels.TopologyRelation;
import io.clusterinfra.rca.webconsole.persistence.TopologyRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class TopologyService {
    private final TopologyExtractor extractor;
    private final TopologyRepository repository;
    private final RcaConsoleProperties properties;

    public TopologyService(
        TopologyExtractor extractor,
        TopologyRepository repository,
        RcaConsoleProperties properties
    ) {
        this.extractor = extractor;
        this.repository = repository;
        this.properties = properties;
    }

    public void observe(EvidenceBundle evidence) {
        if (!properties.getTopology().isEnabled()) {
            return;
        }
        TopologyObservation observation = extractor.extract(evidence);
        if (!observation.entities().isEmpty() || !observation.relations().isEmpty()) {
            repository.save(observation);
        }
    }

    public ClusterTopology current(String clusterId) {
        if (!properties.getTopology().isEnabled()) {
            return empty(clusterId);
        }
        Instant from = Instant.now().minusSeconds(
            Math.max(1, properties.getTopology().getLookbackHours()) * 3600L
        );
        List<TopologyObservation> observations = repository.listRecent(
            clusterId,
            from,
            properties.getTopology().getObservationLimit()
        );
        Map<String, TopologyEntity> entities = new LinkedHashMap<>();
        Map<String, TopologyRelation> relations = new LinkedHashMap<>();
        boolean complete = false;
        Instant observedAt = null;
        List<TopologyObservation> chronological = new ArrayList<>(observations);
        java.util.Collections.reverse(chronological);
        for (TopologyObservation observation : chronological) {
            if (observation.nodeInventoryCollected()) {
                expireMissingNodes(entities, relations, observation);
            }
            if (observation.podInventoryCollected()) {
                expireNodePods(entities, relations, observation.sourceNodeName());
            }
            if (observation.inventoryCollected() && observation.inventoryComplete()) {
                expireClusterInventory(entities, relations);
            }
            observation.entities().forEach(entity -> entities.put(entity.id(), entity));
            observation.relations().forEach(relation ->
                relations.put(relationKey(relation), relation)
            );
            if (observation.inventoryCollected()) {
                complete = observation.inventoryComplete();
            }
            if (observedAt == null || observation.observedAt().isAfter(observedAt)) {
                observedAt = observation.observedAt();
            }
        }
        rebuildSelectorRelations(entities, relations);
        pruneOrphans(entities, relations);
        List<String> nodes = entities.values().stream()
            .filter(entity -> "Node".equals(entity.kind()))
            .map(TopologyEntity::name)
            .sorted()
            .toList();
        List<String> services = entities.values().stream()
            .filter(entity -> "Service".equals(entity.kind()))
            .map(entity -> entity.namespace() + "/" + entity.name())
            .sorted()
            .toList();
        return new ClusterTopology(
            clusterId,
            observedAt,
            List.copyOf(entities.values()),
            List.copyOf(relations.values()),
            nodes,
            services,
            complete
        );
    }

    private void expireMissingNodes(
        Map<String, TopologyEntity> entities,
        Map<String, TopologyRelation> relations,
        TopologyObservation observation
    ) {
        Set<String> observedNodeIds = observation.entities().stream()
            .filter(entity -> "Node".equals(entity.kind()))
            .map(TopologyEntity::id)
            .collect(java.util.stream.Collectors.toSet());
        Set<String> removedNodeIds = entities.values().stream()
            .filter(entity -> "Node".equals(entity.kind()))
            .map(TopologyEntity::id)
            .filter(nodeId -> !observedNodeIds.contains(nodeId))
            .collect(java.util.stream.Collectors.toSet());
        removeEntitiesAndRelations(entities, relations, removedNodeIds);
    }

    private void expireNodePods(
        Map<String, TopologyEntity> entities,
        Map<String, TopologyRelation> relations,
        String nodeName
    ) {
        if (nodeName == null || nodeName.isBlank()) {
            return;
        }
        String sourceNodeId = nodeId(nodeName);
        Set<String> removedPodIds = new LinkedHashSet<>();
        entities.values().stream()
            .filter(entity -> "Pod".equals(entity.kind()))
            .filter(entity -> nodeName.equals(entity.nodeName()))
            .map(TopologyEntity::id)
            .forEach(removedPodIds::add);
        relations.values().stream()
            .filter(relation -> sourceNodeId.equals(relation.source()))
            .filter(relation -> "hosts".equals(relation.relationship()))
            .map(TopologyRelation::target)
            .forEach(removedPodIds::add);
        removeEntitiesAndRelations(entities, relations, removedPodIds);
    }

    private void expireClusterInventory(
        Map<String, TopologyEntity> entities,
        Map<String, TopologyRelation> relations
    ) {
        Set<String> serviceIds = entities.values().stream()
            .filter(entity -> "Service".equals(entity.kind()))
            .map(TopologyEntity::id)
            .collect(java.util.stream.Collectors.toSet());
        removeEntitiesAndRelations(entities, relations, serviceIds);
        relations.entrySet().removeIf(entry -> Set.of(
            "selects",
            "routes_to",
            "has_endpoint_on"
        ).contains(entry.getValue().relationship()));
    }

    private void rebuildSelectorRelations(
        Map<String, TopologyEntity> entities,
        Map<String, TopologyRelation> relations
    ) {
        relations.entrySet().removeIf(entry ->
            "selects".equals(entry.getValue().relationship())
        );
        List<TopologyEntity> pods = entities.values().stream()
            .filter(entity -> "Pod".equals(entity.kind()))
            .toList();
        entities.values().stream()
            .filter(entity -> "Service".equals(entity.kind()))
            .forEach(service -> {
                Map<String, String> selector = stringMap(service.attributes().get("selector"));
                if (selector.isEmpty()) {
                    return;
                }
                pods.stream()
                    .filter(pod -> service.namespace().equals(pod.namespace()))
                    .filter(pod -> selector.entrySet().stream()
                        .allMatch(entry -> entry.getValue().equals(pod.labels().get(entry.getKey()))))
                    .forEach(pod -> {
                        TopologyRelation relation = new TopologyRelation(
                            service.id(),
                            pod.id(),
                            "selects",
                            0.9,
                            "merged_topology.service_selector"
                        );
                        relations.put(relationKey(relation), relation);
                    });
            });
    }

    private void pruneOrphans(
        Map<String, TopologyEntity> entities,
        Map<String, TopologyRelation> relations
    ) {
        relations.entrySet().removeIf(entry ->
            !entities.containsKey(entry.getValue().source())
                || !entities.containsKey(entry.getValue().target())
        );
        Set<String> ownerIds = relations.values().stream()
            .filter(relation -> "owns".equals(relation.relationship()))
            .map(TopologyRelation::source)
            .collect(java.util.stream.Collectors.toSet());
        Set<String> orphanIds = entities.values().stream()
            .filter(entity -> !Set.of("Node", "Pod", "Service").contains(entity.kind()))
            .map(TopologyEntity::id)
            .filter(entityId -> !ownerIds.contains(entityId))
            .collect(java.util.stream.Collectors.toSet());
        removeEntitiesAndRelations(entities, relations, orphanIds);
    }

    private void removeEntitiesAndRelations(
        Map<String, TopologyEntity> entities,
        Map<String, TopologyRelation> relations,
        Set<String> entityIds
    ) {
        if (entityIds.isEmpty()) {
            return;
        }
        entityIds.forEach(entities::remove);
        relations.entrySet().removeIf(entry ->
            entityIds.contains(entry.getValue().source())
                || entityIds.contains(entry.getValue().target())
        );
    }

    private Map<String, String> stringMap(Object value) {
        if (!(value instanceof Map<?, ?> source)) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        source.forEach((key, item) -> {
            if (key != null && item != null) {
                result.put(String.valueOf(key), String.valueOf(item));
            }
        });
        return Map.copyOf(result);
    }

    public Map<String, Object> enrichScope(
        String clusterId,
        String nodeName,
        Map<String, Object> currentScope
    ) {
        ClusterTopology topology = current(clusterId);
        if (topology.entities().isEmpty()) {
            return currentScope;
        }
        Set<String> podIds = hostedPods(topology, nodeName);
        Set<String> affectedServices = servicesForPods(topology, podIds, nodeName);
        Set<String> affectedWorkloads = sourcesForTargets(topology, podIds, "owns");
        Set<String> affectedPods = new LinkedHashSet<>();
        Set<String> namespaces = new LinkedHashSet<>();
        Map<String, TopologyEntity> entities = entities(topology);
        podIds.forEach(podId -> {
            TopologyEntity pod = entities.get(podId);
            if (pod != null) {
                affectedPods.add(pod.namespace() + "/" + pod.name());
                namespaces.add(pod.namespace());
            }
        });

        Map<String, Object> enriched = new LinkedHashMap<>(currentScope);
        if (!affectedPods.isEmpty()) {
            enriched.put("affected_pods", List.copyOf(affectedPods));
            enriched.put("affected_namespaces", List.copyOf(namespaces));
        }
        if (!affectedWorkloads.isEmpty()) {
            enriched.put("affected_workloads", displayNames(affectedWorkloads, entities));
        }
        enriched.put("affected_services", displayNames(affectedServices, entities));
        enriched.put(
            "service_impact_assessment",
            affectedServices.isEmpty()
                ? "No Service selector or EndpointSlice relationship was confirmed for pods on the affected node."
                : affectedServices.size()
                    + " Service(s) have selector or EndpointSlice relationships to pods on the affected node."
        );
        enriched.put("topology_inventory_complete", topology.inventoryComplete());
        enriched.put("topology_observed_at", topology.observedAt());
        return Map.copyOf(enriched);
    }

    public NodeConnection connection(String clusterId, String leftNode, String rightNode) {
        if (leftNode == null || rightNode == null || leftNode.equals(rightNode)) {
            return new NodeConnection(true, "same_node", "signals occurred on the same node", 1.0, List.of());
        }
        ClusterTopology topology = current(clusterId);
        Map<String, TopologyEntity> entities = entities(topology);
        TopologyEntity left = entities.get(nodeId(leftNode));
        TopologyEntity right = entities.get(nodeId(rightNode));
        if (left == null || right == null) {
            return NodeConnection.none();
        }
        Set<String> leftServices = servicesForPods(topology, hostedPods(topology, leftNode), leftNode);
        Set<String> rightServices = servicesForPods(topology, hostedPods(topology, rightNode), rightNode);
        Set<String> shared = new LinkedHashSet<>(leftServices);
        shared.retainAll(rightServices);
        if (!shared.isEmpty()) {
            return new NodeConnection(
                true,
                "topology_shared_service",
                "nodes host endpoints for the same Service",
                0.95,
                displayNames(shared, entities)
            );
        }
        boolean controlPlanePeers = left.roles().contains("control-plane")
            && right.roles().contains("control-plane");
        if (controlPlanePeers) {
            return new NodeConnection(
                true,
                "topology_control_plane_peers",
                "signals occurred on control-plane peers",
                0.9,
                List.of()
            );
        }
        return NodeConnection.none();
    }

    private Set<String> hostedPods(ClusterTopology topology, String nodeName) {
        String nodeId = nodeId(nodeName);
        Set<String> pods = new LinkedHashSet<>();
        topology.relations().stream()
            .filter(relation -> relation.source().equals(nodeId))
            .filter(relation -> "hosts".equals(relation.relationship()))
            .forEach(relation -> pods.add(relation.target()));
        return pods;
    }

    private Set<String> servicesForPods(
        ClusterTopology topology,
        Set<String> podIds,
        String nodeName
    ) {
        Set<String> services = new LinkedHashSet<>();
        String nodeId = nodeId(nodeName);
        topology.relations().stream()
            .filter(relation -> "selects".equals(relation.relationship())
                || "routes_to".equals(relation.relationship())
                || "has_endpoint_on".equals(relation.relationship()))
            .filter(relation -> podIds.contains(relation.target()) || nodeId.equals(relation.target()))
            .forEach(relation -> services.add(relation.source()));
        return services;
    }

    private Set<String> sourcesForTargets(
        ClusterTopology topology,
        Set<String> targets,
        String relationship
    ) {
        Set<String> sources = new LinkedHashSet<>();
        topology.relations().stream()
            .filter(relation -> relationship.equals(relation.relationship()))
            .filter(relation -> targets.contains(relation.target()))
            .forEach(relation -> sources.add(relation.source()));
        return sources;
    }

    private List<String> displayNames(
        Set<String> ids,
        Map<String, TopologyEntity> entities
    ) {
        return ids.stream()
            .map(entities::get)
            .filter(java.util.Objects::nonNull)
            .map(entity -> entity.namespace().isBlank()
                ? entity.kind() + "/" + entity.name()
                : entity.namespace() + "/" + entity.name())
            .sorted()
            .toList();
    }

    private Map<String, TopologyEntity> entities(ClusterTopology topology) {
        Map<String, TopologyEntity> entities = new LinkedHashMap<>();
        topology.entities().forEach(entity -> entities.put(entity.id(), entity));
        return entities;
    }

    private String relationKey(TopologyRelation relation) {
        return relation.source() + "|" + relation.target() + "|" + relation.relationship();
    }

    private String nodeId(String nodeName) {
        return "node:" + nodeName;
    }

    private ClusterTopology empty(String clusterId) {
        return new ClusterTopology(
            clusterId,
            null,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            false
        );
    }

    public record NodeConnection(
        boolean related,
        String ruleId,
        String relationship,
        double confidence,
        List<String> sharedServices
    ) {
        public static NodeConnection none() {
            return new NodeConnection(false, "topology_unrelated", "no topology relation", 0.0, List.of());
        }
    }
}
