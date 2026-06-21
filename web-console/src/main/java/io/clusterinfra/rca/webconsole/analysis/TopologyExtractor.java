package io.clusterinfra.rca.webconsole.analysis;

import io.clusterinfra.rca.webconsole.domain.RcaModels.EvidenceBundle;
import io.clusterinfra.rca.webconsole.domain.RcaModels.TopologyEntity;
import io.clusterinfra.rca.webconsole.domain.RcaModels.TopologyObservation;
import io.clusterinfra.rca.webconsole.domain.RcaModels.TopologyRelation;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class TopologyExtractor {
    public TopologyObservation extract(EvidenceBundle evidence) {
        Map<String, Object> kubernetes = map(evidence.collectors().get("kubernetes"));
        if (kubernetes.isEmpty()) {
            return empty(evidence);
        }
        Map<String, TopologyEntity> entities = new LinkedHashMap<>();
        Map<String, TopologyRelation> relations = new LinkedHashMap<>();

        List<Map<String, Object>> nodes = items(kubernetes.get("nodes"));
        for (Map<String, Object> node : nodes) {
            addNode(entities, node);
        }
        if (!text(kubernetes.get("node_name")).isBlank()) {
            String nodeName = text(kubernetes.get("node_name"));
            String nodeId = id("node", "", nodeName);
            entities.putIfAbsent(nodeId, new TopologyEntity(
                nodeId,
                "Node",
                "",
                nodeName,
                nodeName,
                nodeRoles(strings(kubernetes.get("node_labels"))),
                strings(kubernetes.get("node_labels")),
                Map.of("ready", Boolean.TRUE.equals(kubernetes.get("node_ready")))
            ));
        }

        List<Map<String, Object>> pods = items(kubernetes.get("pods"));
        for (Map<String, Object> pod : pods) {
            addPod(entities, relations, pod);
        }

        List<Map<String, Object>> services = items(kubernetes.get("services"));
        for (Map<String, Object> service : services) {
            addService(entities, service);
        }
        connectServiceSelectors(entities, relations);

        List<Map<String, Object>> endpointSlices = items(kubernetes.get("endpoint_slices"));
        for (Map<String, Object> endpointSlice : endpointSlices) {
            addEndpointSliceRelations(entities, relations, endpointSlice);
        }

        boolean nodeInventoryCollected = responseOk(kubernetes.get("nodes"));
        boolean podInventoryCollected = responseOk(kubernetes.get("pods"));
        boolean inventoryCollected = Boolean.TRUE.equals(
            kubernetes.get("topology_inventory_collected")
        );
        boolean inventoryComplete = Boolean.TRUE.equals(
            kubernetes.get("topology_inventory_complete")
        ) || (
            !kubernetes.containsKey("topology_inventory_complete")
                && inventoryCollected
                && !Boolean.TRUE.equals(kubernetes.get("topology_inventory_truncated"))
        );
        return new TopologyObservation(
            "topology-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8),
            evidence.clusterId(),
            evidence.evidenceId(),
            evidence.nodeName(),
            evidence.collectedAt(),
            List.copyOf(entities.values()),
            List.copyOf(relations.values()),
            nodeInventoryCollected,
            podInventoryCollected,
            inventoryCollected,
            inventoryComplete
        );
    }

    private void addNode(Map<String, TopologyEntity> entities, Map<String, Object> node) {
        Map<String, Object> metadata = map(node.get("metadata"));
        String name = text(metadata.get("name"));
        if (name.isBlank()) {
            return;
        }
        Map<String, String> labels = strings(metadata.get("labels"));
        Map<String, Object> status = map(node.get("status"));
        String nodeId = id("node", "", name);
        entities.put(nodeId, new TopologyEntity(
            nodeId,
            "Node",
            "",
            name,
            name,
            nodeRoles(labels),
            labels,
            Map.of(
                "ready", ready(status),
                "addresses", list(status.get("addresses"))
            )
        ));
    }

    private void addPod(
        Map<String, TopologyEntity> entities,
        Map<String, TopologyRelation> relations,
        Map<String, Object> pod
    ) {
        Map<String, Object> metadata = map(pod.get("metadata"));
        Map<String, Object> spec = map(pod.get("spec"));
        Map<String, Object> status = map(pod.get("status"));
        String namespace = defaultNamespace(text(metadata.get("namespace")));
        String name = text(metadata.get("name"));
        if (name.isBlank()) {
            return;
        }
        String nodeName = text(spec.get("nodeName"));
        String podId = id("pod", namespace, name);
        entities.put(podId, new TopologyEntity(
            podId,
            "Pod",
            namespace,
            name,
            nodeName,
            List.of(),
            strings(metadata.get("labels")),
            Map.of("phase", text(status.get("phase")))
        ));
        if (!nodeName.isBlank()) {
            relation(
                relations,
                id("node", "", nodeName),
                podId,
                "hosts",
                1.0,
                "kubernetes.pods"
            );
        }
        for (Map<String, Object> owner : maps(metadata.get("ownerReferences"))) {
            String ownerName = text(owner.get("name"));
            String ownerKind = text(owner.get("kind"));
            if (ownerName.isBlank()) {
                continue;
            }
            String ownerId = id(ownerKind.isBlank() ? "workload" : ownerKind, namespace, ownerName);
            entities.putIfAbsent(ownerId, new TopologyEntity(
                ownerId,
                ownerKind.isBlank() ? "Workload" : ownerKind,
                namespace,
                ownerName,
                "",
                List.of(),
                Map.of(),
                Map.of()
            ));
            relation(relations, ownerId, podId, "owns", 1.0, "kubernetes.pods.metadata.ownerReferences");
        }
    }

    private void addService(Map<String, TopologyEntity> entities, Map<String, Object> service) {
        Map<String, Object> metadata = map(service.get("metadata"));
        Map<String, Object> spec = map(service.get("spec"));
        String namespace = defaultNamespace(text(metadata.get("namespace")));
        String name = text(metadata.get("name"));
        if (name.isBlank()) {
            return;
        }
        String serviceId = id("service", namespace, name);
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("selector", strings(spec.get("selector")));
        attributes.put("type", text(spec.get("type")));
        entities.put(serviceId, new TopologyEntity(
            serviceId,
            "Service",
            namespace,
            name,
            "",
            List.of(),
            strings(metadata.get("labels")),
            Map.copyOf(attributes)
        ));
    }

    private void connectServiceSelectors(
        Map<String, TopologyEntity> entities,
        Map<String, TopologyRelation> relations
    ) {
        List<TopologyEntity> pods = entities.values().stream()
            .filter(entity -> "Pod".equals(entity.kind()))
            .toList();
        entities.values().stream()
            .filter(entity -> "Service".equals(entity.kind()))
            .forEach(service -> {
                Map<String, String> selector = strings(service.attributes().get("selector"));
                if (selector.isEmpty()) {
                    return;
                }
                pods.stream()
                    .filter(pod -> pod.namespace().equals(service.namespace()))
                    .filter(pod -> matches(selector, pod.labels()))
                    .forEach(pod -> relation(
                        relations,
                        service.id(),
                        pod.id(),
                        "selects",
                        0.9,
                        "kubernetes.services.spec.selector"
                    ));
            });
    }

    private void addEndpointSliceRelations(
        Map<String, TopologyEntity> entities,
        Map<String, TopologyRelation> relations,
        Map<String, Object> endpointSlice
    ) {
        Map<String, Object> metadata = map(endpointSlice.get("metadata"));
        String namespace = defaultNamespace(text(metadata.get("namespace")));
        String serviceName = strings(metadata.get("labels")).getOrDefault(
            "kubernetes.io/service-name",
            ""
        );
        if (serviceName.isBlank()) {
            return;
        }
        String serviceId = id("service", namespace, serviceName);
        entities.putIfAbsent(serviceId, new TopologyEntity(
            serviceId,
            "Service",
            namespace,
            serviceName,
            "",
            List.of(),
            Map.of(),
            Map.of()
        ));
        for (Map<String, Object> endpoint : maps(endpointSlice.get("endpoints"))) {
            Map<String, Object> targetRef = map(endpoint.get("targetRef"));
            String targetName = text(targetRef.get("name"));
            String targetKind = text(targetRef.get("kind"));
            String targetNamespace = defaultNamespace(text(targetRef.getOrDefault("namespace", namespace)));
            if ("Pod".equalsIgnoreCase(targetKind) && !targetName.isBlank()) {
                relation(
                    relations,
                    serviceId,
                    id("pod", targetNamespace, targetName),
                    "routes_to",
                    1.0,
                    "kubernetes.endpoint_slices.endpoints.targetRef"
                );
            }
            String nodeName = text(endpoint.get("nodeName"));
            if (!nodeName.isBlank()) {
                relation(
                    relations,
                    serviceId,
                    id("node", "", nodeName),
                    "has_endpoint_on",
                    0.95,
                    "kubernetes.endpoint_slices.endpoints.nodeName"
                );
            }
        }
    }

    private boolean ready(Map<String, Object> status) {
        for (Map<String, Object> condition : maps(status.get("conditions"))) {
            if ("Ready".equals(text(condition.get("type")))) {
                return "True".equalsIgnoreCase(text(condition.get("status")));
            }
        }
        return false;
    }

    private List<String> nodeRoles(Map<String, String> labels) {
        Set<String> roles = new LinkedHashSet<>();
        labels.keySet().forEach(key -> {
            if (key.equals("node-role.kubernetes.io/control-plane")
                || key.equals("node-role.kubernetes.io/master")) {
                roles.add("control-plane");
            } else if (key.equals("node-role.kubernetes.io/etcd")) {
                roles.add("etcd");
            } else if (key.startsWith("node-role.kubernetes.io/")) {
                roles.add(key.substring("node-role.kubernetes.io/".length()));
            }
        });
        if (roles.isEmpty()) {
            roles.add("worker");
        }
        return List.copyOf(roles);
    }

    private void relation(
        Map<String, TopologyRelation> relations,
        String source,
        String target,
        String relationship,
        double confidence,
        String evidencePath
    ) {
        String key = source + "|" + target + "|" + relationship;
        relations.put(key, new TopologyRelation(source, target, relationship, confidence, evidencePath));
    }

    private boolean matches(Map<String, String> selector, Map<String, String> labels) {
        return selector.entrySet().stream()
            .allMatch(entry -> entry.getValue().equals(labels.get(entry.getKey())));
    }

    private List<Map<String, Object>> items(Object response) {
        return maps(map(map(response).get("data")).get("items"));
    }

    private boolean responseOk(Object response) {
        return Boolean.TRUE.equals(map(response).get("ok"));
    }

    private List<Map<String, Object>> maps(Object value) {
        if (!(value instanceof Collection<?> collection)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        collection.forEach(item -> {
            Map<String, Object> mapped = map(item);
            if (!mapped.isEmpty()) {
                result.add(mapped);
            }
        });
        return result;
    }

    private List<Object> list(Object value) {
        return value instanceof List<?> values ? List.copyOf(values) : List.of();
    }

    private Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> source)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private Map<String, String> strings(Object value) {
        if (!(value instanceof Map<?, ?> source)) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        source.forEach((key, item) -> {
            if (item != null) {
                result.put(String.valueOf(key), String.valueOf(item));
            }
        });
        return result;
    }

    private String defaultNamespace(String value) {
        return value == null || value.isBlank() ? "default" : value;
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String id(String kind, String namespace, String name) {
        String normalizedKind = kind == null || kind.isBlank()
            ? "resource"
            : kind.toLowerCase(Locale.ROOT);
        return namespace == null || namespace.isBlank()
            ? normalizedKind + ":" + name
            : normalizedKind + ":" + namespace + "/" + name;
    }

    private TopologyObservation empty(EvidenceBundle evidence) {
        return new TopologyObservation(
            "topology-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8),
            evidence.clusterId(),
            evidence.evidenceId(),
            evidence.nodeName(),
            evidence.collectedAt() == null ? Instant.now() : evidence.collectedAt(),
            List.of(),
            List.of(),
            false,
            false,
            false,
            false
        );
    }
}
