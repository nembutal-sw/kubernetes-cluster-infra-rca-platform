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
public class CniFailureDetector implements SignalDetector {
    @Override
    public String id() {
        return "cni-failure";
    }

    @Override
    public List<Signal> detect(AnalysisContext context) {
        List<Signal> signals = new ArrayList<>();
        detectConfigFailure(context).stream().findFirst().ifPresent(signals::add);
        detectDaemonSetNotScheduled(context).stream().findFirst().ifPresent(signals::add);
        detectDaemonSetUnavailable(context).stream().findFirst().ifPresent(signals::add);
        detectCniPodNotRunning(context).stream().findFirst().ifPresent(signals::add);
        detectMtuMismatch(context).stream().findFirst().ifPresent(signals::add);
        return signals;
    }

    private List<Signal> detectConfigFailure(AnalysisContext context) {
        for (Map.Entry<String, Object> entry : context.flattened().entrySet()) {
            String key = entry.getKey().toLowerCase(Locale.ROOT);
            Object value = entry.getValue();
            if (key.equals("cni.config_dir_exists") && Boolean.FALSE.equals(value)) {
                return List.of(configSignal(value, entry.getKey(), "CNI configuration directory was not found."));
            }
            if (key.equals("cni.configured") && Boolean.FALSE.equals(value)) {
                return List.of(configSignal(value, entry.getKey(), "CNI configuration is marked as disabled or invalid."));
            }
            if (key.equals("cni.plugin_errors_detected") && Boolean.TRUE.equals(value)) {
                return List.of(configSignal(value, entry.getKey(), "CNI plugin error markers were detected."));
            }
            if ((key.startsWith("cni.parse_errors[") || key.startsWith("cni.access_errors[")) && value != null) {
                return List.of(configSignal(value, entry.getKey(), "CNI configuration could not be read or parsed."));
            }
            if (key.equals("cni.status") && isFailureStatus(value)) {
                return List.of(configSignal(value, entry.getKey(), "CNI configuration or health check indicates an error."));
            }
        }
        return List.of();
    }

    private Signal configSignal(Object observed, String field, String interpretation) {
        return DetectorSupport.matchedSignal(
            "cni_config_invalid",
            "cni",
            "critical",
            observed,
            List.of(field),
            interpretation,
            "Inspect CNI configuration files, plugin logs, node routes, and kube-system CNI pods.",
            "cni"
        );
    }

    private List<Signal> detectDaemonSetNotScheduled(AnalysisContext context) {
        List<?> notScheduled = list(context.collectors()
            .getOrDefault("kubernetes", Map.of()), "cni_daemonsets_not_scheduled");
        if (!notScheduled.isEmpty()) {
            return List.of(DetectorSupport.matchedSignal(
                "cni_daemonset_not_scheduled",
                "cni",
                "critical",
                notScheduled.size(),
                List.of("kubernetes.cni_daemonsets_not_scheduled"),
                "CNI DaemonSet exists but is not scheduled to any node.",
                "Inspect DaemonSet nodeSelector, tolerations, taints, and recent manifest changes.",
                "cni", "kubernetes"
            ));
        }
        return countSignal(
            context,
            "kubernetes.cni_daemonset_not_scheduled_count",
            "cni_daemonset_not_scheduled",
            "CNI DaemonSet exists but is not scheduled to any node.",
            "Inspect DaemonSet nodeSelector, tolerations, taints, and recent manifest changes."
        );
    }

    private List<Signal> detectDaemonSetUnavailable(AnalysisContext context) {
        List<?> unavailable = list(context.collectors()
            .getOrDefault("kubernetes", Map.of()), "cni_daemonsets_unavailable");
        if (!unavailable.isEmpty()) {
            return List.of(DetectorSupport.matchedSignal(
                "cni_daemonset_unavailable",
                "cni",
                "critical",
                unavailable.size(),
                List.of("kubernetes.cni_daemonsets_unavailable"),
                "CNI DaemonSet pods are not ready on all intended nodes.",
                "Inspect CNI pod status, image pulls, host mounts, privileges, taints, and node events.",
                "cni", "kubernetes"
            ));
        }
        return countSignal(
            context,
            "kubernetes.cni_daemonset_unavailable_count",
            "cni_daemonset_unavailable",
            "CNI DaemonSet pods are not ready on all intended nodes.",
            "Inspect CNI pod status, image pulls, host mounts, privileges, taints, and node events."
        );
    }

    private List<Signal> detectCniPodNotRunning(AnalysisContext context) {
        List<?> failures = list(context.collectors().getOrDefault("kubernetes", Map.of()), "cni_non_running_pods");
        if (failures.isEmpty()) {
            return List.of();
        }
        return List.of(DetectorSupport.matchedSignal(
            "cni_pod_not_running",
            "cni",
            "critical",
            failures.size(),
            List.of("kubernetes.cni_non_running_pods"),
            "One or more CNI pods on the node are not running.",
            "Inspect the CNI pod events, init containers, image state, host mounts, and permissions.",
            "cni", "kubernetes"
        ));
    }

    private List<Signal> detectMtuMismatch(AnalysisContext context) {
        for (Map.Entry<String, Object> entry : context.flattened().entrySet()) {
            String key = entry.getKey().toLowerCase(Locale.ROOT);
            String value = AnalysisContext.string(entry.getValue());
            if (key.contains("mtu")
                && (key.contains("mismatch") || key.contains("inconsistent"))
                && (Boolean.TRUE.equals(entry.getValue()) || "true".equalsIgnoreCase(value))) {
                return List.of(DetectorSupport.matchedSignal(
                    "cni_mtu_values_inconsistent",
                    "cni",
                    "warning",
                    entry.getValue(),
                    List.of(entry.getKey()),
                    "Host and CNI interface MTU values are inconsistent.",
                    "Verify overlay overhead and propose reviewed CNI MTU changes through GitOps.",
                    "cni", "network"
                ));
            }
        }
        if (context.contains("mtu mismatch") || context.contains("inconsistent mtu")
            || context.contains("mtu values are different")) {
            return List.of(DetectorSupport.matchedSignal(
                "cni_mtu_values_inconsistent",
                "cni",
                "warning",
                "MTU mismatch log match",
                List.of("collector text"),
                "Host and CNI interface MTU values are inconsistent.",
                "Verify overlay overhead and propose reviewed CNI MTU changes through GitOps.",
                "cni", "network"
            ));
        }
        return List.of();
    }

    private List<Signal> countSignal(
        AnalysisContext context,
        String field,
        String signalName,
        String interpretation,
        String nextStep
    ) {
        Object value = context.flattened().get(field);
        if (AnalysisContext.toDouble(value).isPresent()
            && AnalysisContext.toDouble(value).getAsDouble() > 0) {
            return List.of(DetectorSupport.matchedSignal(
                signalName,
                "cni",
                "critical",
                value,
                List.of(field),
                interpretation,
                nextStep,
                "cni", "kubernetes"
            ));
        }
        return List.of();
    }

    private List<?> list(Object root, String key) {
        if (root instanceof Map<?, ?> map && map.get(key) instanceof List<?> values) {
            return values;
        }
        return List.of();
    }

    private boolean isFailureStatus(Object value) {
        String normalized = AnalysisContext.string(value).toLowerCase(Locale.ROOT);
        return normalized.equals("failed")
            || normalized.equals("unhealthy")
            || normalized.equals("down")
            || normalized.equals("error");
    }
}
