package io.clusterinfra.rca.webconsole.analysis;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class ImpactScopeAnalyzer {
    public Map<String, Object> analyze(Map<String, Object> collectors, String nodeName) {
        Set<String> pods = new LinkedHashSet<>();
        Set<String> namespaces = new LinkedHashSet<>();
        Set<String> workloads = new LinkedHashSet<>();
        Set<String> observedServices = new LinkedHashSet<>();
        Set<String> evidencePaths = new LinkedHashSet<>();

        Object kubernetes = collectors == null ? null : collectors.get("kubernetes");
        collect(
            kubernetes,
            "kubernetes",
            nodeName,
            pods,
            namespaces,
            workloads,
            observedServices,
            evidencePaths,
            0
        );

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("affected_pods", List.copyOf(pods));
        result.put("affected_namespaces", List.copyOf(namespaces));
        result.put("affected_services", List.of());
        result.put("observed_services", List.copyOf(observedServices));
        result.put(
            "service_impact_assessment",
            observedServices.isEmpty()
                ? "No Service inventory was present in the collected evidence."
                : observedServices.size() + " Service object(s) were observed in the evidence. "
                    + "Endpoint, selector, and traffic correlation were not verified, so service impact is unconfirmed."
        );
        result.put("affected_workloads", List.copyOf(workloads));
        result.put("impact_evidence_paths", List.copyOf(evidencePaths));
        result.put(
            "impact_assessment",
            pods.isEmpty()
                ? "No workload inventory was available in the collected evidence."
                : pods.size() + " pod(s) across " + namespaces.size()
                    + " namespace(s) were observed on the affected node."
        );
        return Map.copyOf(result);
    }

    private void collect(
        Object value,
        String path,
        String nodeName,
        Set<String> pods,
        Set<String> namespaces,
        Set<String> workloads,
        Set<String> services,
        Set<String> evidencePaths,
        int depth
    ) {
        if (value == null || depth > 12) {
            return;
        }
        if (value instanceof List<?> list) {
            for (int index = 0; index < Math.min(list.size(), 200); index++) {
                collect(
                    list.get(index),
                    path + "[" + index + "]",
                    nodeName,
                    pods,
                    namespaces,
                    workloads,
                    services,
                    evidencePaths,
                    depth + 1
                );
            }
            return;
        }
        if (!(value instanceof Map<?, ?> map)) {
            return;
        }

        Map<String, Object> values = stringKeyMap(map);
        Map<String, Object> metadata = mapValue(values.get("metadata"));
        Map<String, Object> spec = mapValue(values.get("spec"));
        String kind = text(values.get("kind"));
        String name = text(metadata.getOrDefault("name", values.get("name")));
        String namespace = text(metadata.getOrDefault("namespace", values.get("namespace")));
        String itemNode = text(spec.getOrDefault("nodeName", values.get("node_name")));
        boolean nodeMatches = itemNode.isBlank() || nodeName == null || nodeName.equals(itemNode);
        if (nodeMatches && isPod(path, kind, metadata, spec, values) && !name.isBlank()) {
            pods.add(namespace.isBlank() ? name : namespace + "/" + name);
            if (!namespace.isBlank()) {
                namespaces.add(namespace);
            }
            evidencePaths.add(path);
            ownerReferences(metadata).forEach(owner -> workloads.add(owner));
        }
        if ("Service".equalsIgnoreCase(kind) && !name.isBlank()) {
            services.add(namespace.isBlank() ? name : namespace + "/" + name);
            evidencePaths.add(path);
        }

        values.forEach((key, child) -> collect(
            child,
            path + "." + key,
            nodeName,
            pods,
            namespaces,
            workloads,
            services,
            evidencePaths,
            depth + 1
        ));
    }

    private boolean isPod(
        String path,
        String kind,
        Map<String, Object> metadata,
        Map<String, Object> spec,
        Map<String, Object> values
    ) {
        if ("Pod".equalsIgnoreCase(kind)) {
            return true;
        }
        String normalized = path.toLowerCase();
        return normalized.contains("pod")
            && (!metadata.isEmpty() || values.containsKey("phase"))
            && (spec.containsKey("nodeName") || values.containsKey("node_name")
                || values.containsKey("phase") || normalized.contains("pods.data.items"));
    }

    private List<String> ownerReferences(Map<String, Object> metadata) {
        Object raw = metadata.get("ownerReferences");
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<String> owners = new ArrayList<>();
        for (Object item : list) {
            Map<String, Object> owner = mapValue(item);
            String kind = text(owner.get("kind"));
            String name = text(owner.get("name"));
            if (!name.isBlank()) {
                owners.add(kind.isBlank() ? name : kind + "/" + name);
            }
        }
        return owners;
    }

    private Map<String, Object> stringKeyMap(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private Map<String, Object> mapValue(Object value) {
        return value instanceof Map<?, ?> map ? stringKeyMap(map) : Map.of();
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
