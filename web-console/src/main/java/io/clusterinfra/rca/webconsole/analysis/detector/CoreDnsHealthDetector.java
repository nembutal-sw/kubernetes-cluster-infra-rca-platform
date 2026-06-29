package io.clusterinfra.rca.webconsole.analysis.detector;

import io.clusterinfra.rca.webconsole.analysis.AnalysisContext;
import io.clusterinfra.rca.webconsole.analysis.DetectorSupport;
import io.clusterinfra.rca.webconsole.analysis.Signal;
import io.clusterinfra.rca.webconsole.analysis.SignalDetector;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class CoreDnsHealthDetector implements SignalDetector {
    @Override
    public String id() {
        return "coredns-health";
    }

    @Override
    public List<Signal> detect(AnalysisContext context) {
        Map<String, Object> kubernetes = map(context.collectors().get("kubernetes"));
        if (kubernetes.isEmpty()) {
            return List.of();
        }

        List<Signal> signals = new ArrayList<>();
        corednsEndpointSummary(kubernetes).ifNoReadyEndpoints(signals);
        endpointStatus(kubernetes).ifNoReadyEndpoints(signals);
        corednsPodFailures(kubernetes, signals);
        return signals;
    }

    private EndpointStatus corednsEndpointSummary(Map<String, Object> kubernetes) {
        if (!Boolean.TRUE.equals(kubernetes.get("coredns_service_observed"))) {
            return EndpointStatus.notObserved();
        }
        int endpointCount = integer(kubernetes.get("coredns_endpoint_count"));
        int readyEndpointCount = integer(kubernetes.get("coredns_ready_endpoint_count"));
        return new EndpointStatus(true, endpointCount, readyEndpointCount, "kubernetes.coredns_ready_endpoint_count");
    }

    private EndpointStatus endpointStatus(Map<String, Object> kubernetes) {
        Map<String, Object> endpointSlices = map(kubernetes.get("endpoint_slices"));
        if (!Boolean.TRUE.equals(endpointSlices.get("ok"))) {
            return EndpointStatus.notObserved();
        }
        List<?> items = list(map(endpointSlices.get("data")).get("items"));
        boolean serviceObserved = false;
        int endpointCount = 0;
        int readyEndpointCount = 0;
        for (Object item : items) {
            Map<String, Object> slice = map(item);
            if (!isKubeDnsEndpointSlice(slice)) {
                continue;
            }
            serviceObserved = true;
            for (Object endpointItem : list(slice.get("endpoints"))) {
                endpointCount++;
                Map<String, Object> endpoint = map(endpointItem);
                Map<String, Object> conditions = map(endpoint.get("conditions"));
                Object ready = conditions.get("ready");
                if (ready == null || Boolean.TRUE.equals(ready) || "true".equalsIgnoreCase(String.valueOf(ready))) {
                    readyEndpointCount++;
                }
            }
        }
        return new EndpointStatus(serviceObserved, endpointCount, readyEndpointCount, "kubernetes.endpoint_slices.data.items");
    }

    private void corednsPodFailures(Map<String, Object> kubernetes, List<Signal> signals) {
        List<?> summaryFailures = list(kubernetes.get("coredns_non_running_pods"));
        if (!summaryFailures.isEmpty()) {
            signals.add(DetectorSupport.matchedSignal(
                "coredns_pod_not_running",
                "dns",
                "critical",
                summaryFailures,
                List.of("kubernetes.coredns_non_running_pods"),
                "CoreDNS pod is not running on the node.",
                "Inspect CoreDNS pod events, image state, CNI reachability, and kube-system scheduling.",
                "dns", "kubernetes"
            ));
            return;
        }

        Map<String, Object> pods = map(kubernetes.get("pods"));
        if (!Boolean.TRUE.equals(pods.get("ok"))) {
            return;
        }
        List<?> items = list(map(pods.get("data")).get("items"));
        for (Object item : items) {
            Map<String, Object> pod = map(item);
            if (!isCoreDnsPod(pod)) {
                continue;
            }
            String phase = String.valueOf(map(pod.get("status")).getOrDefault("phase", ""));
            if (!phase.equals("Running") && !phase.equals("Succeeded")) {
                signals.add(DetectorSupport.matchedSignal(
                    "coredns_pod_not_running",
                    "dns",
                    "critical",
                    phase,
                    List.of("kubernetes.pods.data.items"),
                    "CoreDNS pod is not running on the node.",
                    "Inspect CoreDNS pod events, image state, CNI reachability, and kube-system scheduling.",
                    "dns", "kubernetes"
                ));
                return;
            }
        }
    }

    private boolean isKubeDnsEndpointSlice(Map<String, Object> slice) {
        Map<String, Object> metadata = map(slice.get("metadata"));
        Map<String, Object> labels = map(metadata.get("labels"));
        String serviceName = String.valueOf(labels.getOrDefault("kubernetes.io/service-name", ""));
        String name = String.valueOf(metadata.getOrDefault("name", ""));
        return serviceName.equals("kube-dns") || name.startsWith("kube-dns");
    }

    private boolean isCoreDnsPod(Map<String, Object> pod) {
        Map<String, Object> metadata = map(pod.get("metadata"));
        String namespace = String.valueOf(metadata.getOrDefault("namespace", ""));
        String name = String.valueOf(metadata.getOrDefault("name", "")).toLowerCase(Locale.ROOT);
        Map<String, Object> labels = map(metadata.get("labels"));
        String app = String.valueOf(labels.getOrDefault("k8s-app", ""));
        String component = String.valueOf(labels.getOrDefault("k8s-app", labels.getOrDefault("app", "")));
        return namespace.equals("kube-system")
            && (app.equals("kube-dns") || component.equals("coredns") || name.startsWith("coredns-"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of();
    }

    private static List<?> list(Object value) {
        return value instanceof List<?> list ? list : List.of();
    }

    private static int integer(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    private record EndpointStatus(boolean serviceObserved, int endpointCount, int readyEndpointCount, String field) {
        static EndpointStatus notObserved() {
            return new EndpointStatus(false, 0, 0, "kubernetes.endpoint_slices.data.items");
        }

        void ifNoReadyEndpoints(List<Signal> signals) {
            if (!serviceObserved || readyEndpointCount > 0) {
                return;
            }
            signals.add(DetectorSupport.matchedSignal(
                "coredns_no_ready_endpoints",
                "dns",
                "critical",
                "ready_endpoints=" + readyEndpointCount + ", endpoints=" + endpointCount,
                List.of(field),
                "CoreDNS service has no ready endpoints.",
                "Inspect CoreDNS deployment, pods, EndpointSlices, CNI reachability, and kube-system scheduling.",
                "dns", "kubernetes", "endpoint_slices"
            ));
        }
    }
}
