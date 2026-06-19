package io.clusterinfra.rca.webconsole.service;

import io.clusterinfra.rca.webconsole.config.RcaConsoleProperties;
import io.clusterinfra.rca.webconsole.domain.RcaModels.Cluster;
import io.clusterinfra.rca.webconsole.domain.RcaModels.InstallCommandResponse;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class AgentManifestService {
    private static final Pattern KUBERNETES_NAME =
        Pattern.compile("^[a-z0-9]([-a-z0-9]*[a-z0-9])?$");
    private static final String APP_NAME = "cluster-infra-rca-agent";

    private final RcaConsoleProperties properties;

    public AgentManifestService(RcaConsoleProperties properties) {
        this.properties = properties;
    }

    public InstallCommandResponse installCommand(
        Cluster cluster,
        String backendUrl,
        String image,
        String namespace
    ) {
        String validatedImage = validateImage(defaultIfBlank(image, properties.getAgent().getImage()));
        String validatedNamespace = validateKubernetesName(
            defaultIfBlank(namespace, properties.getAgent().getNamespace()),
            "namespace"
        );
        String manifestCommand = "kubectl apply -f manifests/agent-daemonset.yaml";
        List<String> notes = List.of(
            "Provide backend_url to generate a cluster-specific manifest URL.",
            "Without backend_url, apply the repository manifest after configuring its Secret."
        );
        if (backendUrl != null && !backendUrl.isBlank()) {
            String baseUrl = validateBackendUrl(backendUrl);
            String query = query(Map.of(
                "backend_url", baseUrl,
                "image", validatedImage,
                "namespace", validatedNamespace,
                "systemd_collector_mode", "file",
                "agent_token", cluster.bootstrapToken()
            ));
            manifestCommand = "kubectl apply -f \"" + baseUrl + "/api/clusters/" + cluster.clusterId()
                + "/agent-manifest?" + query + "\"";
            notes = List.of(
                "The generated command contains the cluster bootstrap token and must be treated as sensitive.",
                "The DaemonSet uses read-only host mounts and file-based systemd or journal evidence collection."
            );
        }
        return new InstallCommandResponse(
            cluster.clusterId(),
            validatedNamespace,
            List.of(
                "kubectl create namespace " + validatedNamespace + " --dry-run=client -o yaml | kubectl apply -f -",
                "kubectl -n " + validatedNamespace + " create secret generic " + APP_NAME
                    + " --from-literal=cluster-id=" + cluster.clusterId()
                    + " --from-literal=agent-token=" + cluster.bootstrapToken()
                    + " --dry-run=client -o yaml | kubectl apply -f -",
                manifestCommand
            ),
            notes
        );
    }

    public Map<String, Object> manifest(Cluster cluster, ManifestOptions requested) {
        ManifestOptions options = normalize(requested);
        String configMapName = APP_NAME + "-config";
        List<Object> items = new ArrayList<>();
        items.add(map(
            "apiVersion", "v1",
            "kind", "Namespace",
            "metadata", map("name", options.namespace())
        ));
        items.add(map(
            "apiVersion", "v1",
            "kind", "ServiceAccount",
            "metadata", map("name", APP_NAME, "namespace", options.namespace())
        ));
        items.add(clusterRole());
        items.add(clusterRoleBinding(options.namespace()));
        items.add(map(
            "apiVersion", "v1",
            "kind", "ConfigMap",
            "metadata", map(
                "name", configMapName,
                "namespace", options.namespace(),
                "annotations", map(
                    "cluster-infra-rca.io/cluster-id", cluster.clusterId(),
                    "cluster-infra-rca.io/agent-secret-name", APP_NAME
                )
            ),
            "data", map(
                "BACKEND_URL", options.backendUrl(),
                "POLL_INTERVAL_SECONDS", Integer.toString(options.pollIntervalSeconds()),
                "HTTP_TIMEOUT_SECONDS", Integer.toString(options.httpTimeoutSeconds()),
                "COMMAND_TIMEOUT_SECONDS", Integer.toString(options.commandTimeoutSeconds()),
                "RETRY_INITIAL_SECONDS", "2",
                "RETRY_MAX_SECONDS", "120",
                "AGENT_MAX_SPOOL_FILES", "1000",
                "AGENT_MAX_SPOOL_BYTES", "268435456",
                "KUBERNETES_API_TIMEOUT_SECONDS", Integer.toString(options.kubernetesApiTimeoutSeconds()),
                "CONTROL_PLANE_PROBE_PORTS", options.controlPlaneProbePorts(),
                "CONTAINER_RUNTIME_SOCKET_PATHS", options.runtimeSocketPaths(),
                "SYSTEMD_COLLECTOR_MODE", options.systemdCollectorMode(),
                "AGENT_STATE_DIR", "/var/lib/cluster-infra-rca-agent"
            )
        ));
        items.add(daemonSet(options, configMapName));
        return map("apiVersion", "v1", "kind", "List", "items", items);
    }

    private Map<String, Object> clusterRole() {
        return map(
            "apiVersion", "rbac.authorization.k8s.io/v1",
            "kind", "ClusterRole",
            "metadata", map("name", APP_NAME),
            "rules", List.of(
                map("apiGroups", List.of(""), "resources", List.of("nodes", "pods", "events"), "verbs", List.of("get", "list")),
                map("apiGroups", List.of("coordination.k8s.io"), "resources", List.of("leases"), "verbs", List.of("get", "list")),
                map("apiGroups", List.of("metrics.k8s.io"), "resources", List.of("nodes", "pods"), "verbs", List.of("get", "list")),
                map("nonResourceURLs", List.of("/readyz", "/readyz/*", "/livez", "/livez/*"), "verbs", List.of("get"))
            )
        );
    }

    private Map<String, Object> clusterRoleBinding(String namespace) {
        return map(
            "apiVersion", "rbac.authorization.k8s.io/v1",
            "kind", "ClusterRoleBinding",
            "metadata", map("name", APP_NAME),
            "subjects", List.of(map("kind", "ServiceAccount", "name", APP_NAME, "namespace", namespace)),
            "roleRef", map(
                "apiGroup", "rbac.authorization.k8s.io",
                "kind", "ClusterRole",
                "name", APP_NAME
            )
        );
    }

    private Map<String, Object> daemonSet(ManifestOptions options, String configMapName) {
        Map<String, Object> selector = map("app.kubernetes.io/name", APP_NAME);
        Map<String, Object> labels = map(
            "app.kubernetes.io/name", APP_NAME,
            "app.kubernetes.io/part-of", "cluster-infra-rca"
        );
        return map(
            "apiVersion", "apps/v1",
            "kind", "DaemonSet",
            "metadata", map("name", APP_NAME, "namespace", options.namespace(), "labels", labels),
            "spec", map(
                "selector", map("matchLabels", selector),
                "template", map(
                    "metadata", map("labels", labels),
                    "spec", map(
                        "serviceAccountName", APP_NAME,
                        "hostNetwork", true,
                        "hostPID", true,
                        "dnsPolicy", "ClusterFirstWithHostNet",
                        "terminationGracePeriodSeconds", 20,
                        "tolerations", List.of(map("operator", "Exists")),
                        "containers", List.of(map(
                            "name", "agent",
                            "image", options.image(),
                            "imagePullPolicy", "IfNotPresent",
                            "command", List.of("python", "-m", "node_agent.main"),
                            "env", environment(configMapName),
                            "securityContext", map(
                                "runAsUser", 0,
                                "runAsGroup", 0,
                                "readOnlyRootFilesystem", true,
                                "allowPrivilegeEscalation", false
                            ),
                            "volumeMounts", volumeMounts(),
                            "resources", map(
                                "requests", map("cpu", "50m", "memory", "64Mi"),
                                "limits", map("cpu", "500m", "memory", "256Mi")
                            )
                        )),
                        "volumes", volumes()
                    )
                )
            )
        );
    }

    private List<Object> environment(String configMapName) {
        List<Object> environment = new ArrayList<>();
        environment.add(map("name", "PYTHONDONTWRITEBYTECODE", "value", "1"));
        environment.add(map("name", "PYTHONUNBUFFERED", "value", "1"));
        for (String key : List.of(
            "BACKEND_URL",
            "POLL_INTERVAL_SECONDS",
            "HTTP_TIMEOUT_SECONDS",
            "COMMAND_TIMEOUT_SECONDS",
            "RETRY_INITIAL_SECONDS",
            "RETRY_MAX_SECONDS",
            "AGENT_MAX_SPOOL_FILES",
            "AGENT_MAX_SPOOL_BYTES",
            "KUBERNETES_API_TIMEOUT_SECONDS",
            "CONTROL_PLANE_PROBE_PORTS",
            "CONTAINER_RUNTIME_SOCKET_PATHS",
            "SYSTEMD_COLLECTOR_MODE",
            "AGENT_STATE_DIR"
        )) {
            environment.add(map(
                "name", key,
                "valueFrom", map("configMapKeyRef", map("name", configMapName, "key", key))
            ));
        }
        environment.add(map(
            "name", "CLUSTER_ID",
            "valueFrom", map("secretKeyRef", map("name", APP_NAME, "key", "cluster-id"))
        ));
        environment.add(map(
            "name", "AGENT_TOKEN",
            "valueFrom", map("secretKeyRef", map("name", APP_NAME, "key", "agent-token"))
        ));
        environment.add(map(
            "name", "NODE_NAME",
            "valueFrom", map("fieldRef", map("fieldPath", "spec.nodeName"))
        ));
        return environment;
    }

    private List<Object> volumeMounts() {
        return List.of(
            mount("host-root", "/host/root"),
            mount("host-var-log", "/host/var/log"),
            mount("host-run", "/host/run"),
            mount("host-etc", "/host/etc"),
            mount("host-proc", "/host/proc"),
            mount("host-sys", "/host/sys"),
            map("name", "agent-state", "mountPath", "/var/lib/cluster-infra-rca-agent")
        );
    }

    private Map<String, Object> mount(String name, String path) {
        return map("name", name, "mountPath", path, "readOnly", true);
    }

    private List<Object> volumes() {
        return List.of(
            volume("host-root", "/"),
            volume("host-var-log", "/var/log"),
            volume("host-run", "/run"),
            volume("host-etc", "/etc"),
            volume("host-proc", "/proc"),
            volume("host-sys", "/sys"),
            map(
                "name", "agent-state",
                "hostPath", map("path", "/var/lib/cluster-infra-rca-agent", "type", "DirectoryOrCreate")
            )
        );
    }

    private Map<String, Object> volume(String name, String path) {
        return map("name", name, "hostPath", map("path", path));
    }

    private ManifestOptions normalize(ManifestOptions requested) {
        String backendUrl = validateBackendUrl(requested.backendUrl());
        String image = validateImage(defaultIfBlank(requested.image(), properties.getAgent().getImage()));
        String namespace = validateKubernetesName(
            defaultIfBlank(requested.namespace(), properties.getAgent().getNamespace()),
            "namespace"
        );
        int pollInterval = bounded(requested.pollIntervalSeconds(), 5, 3600, "poll_interval_seconds");
        int httpTimeout = bounded(requested.httpTimeoutSeconds(), 1, 3600, "http_timeout_seconds");
        int commandTimeout = bounded(requested.commandTimeoutSeconds(), 1, 3600, "command_timeout_seconds");
        int apiTimeout = bounded(requested.kubernetesApiTimeoutSeconds(), 1, 3600, "kubernetes_api_timeout_seconds");
        String systemdMode = defaultIfBlank(requested.systemdCollectorMode(), "file").toLowerCase();
        if (!Set.of("auto", "command", "file").contains(systemdMode)) {
            throw new IllegalArgumentException("systemd_collector_mode must be one of: auto, command, file");
        }
        return new ManifestOptions(
            backendUrl,
            image,
            namespace,
            pollInterval,
            httpTimeout,
            commandTimeout,
            apiTimeout,
            validatePorts(requested.controlPlaneProbePorts()),
            validateSocketPaths(requested.runtimeSocketPaths()),
            systemdMode
        );
    }

    public String validateBackendUrl(String value) {
        try {
            String normalized = value.trim().replaceAll("/+$", "");
            URI uri = URI.create(normalized);
            if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null) {
                throw new IllegalArgumentException("backend_url must be an absolute http or https URL");
            }
            return normalized;
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("backend_url must be an absolute http or https URL");
        }
    }

    private String validateKubernetesName(String value, String fieldName) {
        String normalized = value.trim();
        if (normalized.length() > 63 || !KUBERNETES_NAME.matcher(normalized).matches()) {
            throw new IllegalArgumentException(fieldName + " must be a valid Kubernetes DNS label");
        }
        return normalized;
    }

    private String validateImage(String value) {
        String image = value.trim();
        if (image.isBlank() || image.length() > 512 || image.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("image must be a container image reference without whitespace");
        }
        return image;
    }

    private int bounded(Integer value, int minimum, int maximum, String fieldName) {
        int actual = value == null ? minimum : value;
        if (actual < minimum || actual > maximum) {
            throw new IllegalArgumentException(fieldName + " must be between " + minimum + " and " + maximum);
        }
        return actual;
    }

    private String validatePorts(String value) {
        String raw = defaultIfBlank(value, "6443,9345");
        LinkedHashSet<String> ports = new LinkedHashSet<>();
        for (String item : raw.split(",")) {
            int port;
            try {
                port = Integer.parseInt(item.trim());
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("control_plane_probe_ports must contain TCP ports");
            }
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("control_plane_probe_ports must contain TCP ports");
            }
            ports.add(Integer.toString(port));
        }
        return String.join(",", ports);
    }

    private String validateSocketPaths(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        for (String item : value.split("[,;]")) {
            String normalized = item.trim();
            String path = normalized.contains("=") ? normalized.substring(normalized.indexOf('=') + 1) : normalized;
            if (!path.startsWith("/") || path.chars().anyMatch(Character::isWhitespace)) {
                throw new IllegalArgumentException("runtime_socket_paths must use absolute host paths");
            }
            paths.add(normalized);
        }
        return String.join(",", paths);
    }

    private String query(Map<String, String> values) {
        return values.entrySet().stream()
            .map(entry -> URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8) + "="
                + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8))
            .collect(java.util.stream.Collectors.joining("&"));
    }

    private String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static Map<String, Object> map(Object... entries) {
        if (entries.length % 2 != 0) {
            throw new IllegalArgumentException("map entries must be key-value pairs");
        }
        Map<String, Object> value = new LinkedHashMap<>();
        for (int index = 0; index < entries.length; index += 2) {
            value.put(String.valueOf(entries[index]), entries[index + 1]);
        }
        return value;
    }

    public record ManifestOptions(
        String backendUrl,
        String image,
        String namespace,
        Integer pollIntervalSeconds,
        Integer httpTimeoutSeconds,
        Integer commandTimeoutSeconds,
        Integer kubernetesApiTimeoutSeconds,
        String controlPlaneProbePorts,
        String runtimeSocketPaths,
        String systemdCollectorMode
    ) {
    }
}
