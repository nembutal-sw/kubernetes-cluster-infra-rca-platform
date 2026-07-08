package io.clusterinfra.rca.webconsole.service;

import io.clusterinfra.rca.webconsole.config.RcaConsoleProperties;
import io.clusterinfra.rca.webconsole.domain.RcaModels.ClusterThresholdSettings;
import io.clusterinfra.rca.webconsole.domain.RcaModels.ClusterThresholdUpdateRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.ThresholdDefinition;
import io.clusterinfra.rca.webconsole.persistence.ClusterThresholdRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class ClusterThresholdService {
    private static final List<ThresholdDefinition> DEFINITIONS = List.of(
        percent("disk.warning.percent", "Disk usage warning", "warning", "disk.critical.percent"),
        percent("disk.critical.percent", "Disk usage critical", "critical", "disk.warning.percent"),
        percent("inode.warning.percent", "Inode usage warning", "warning", "inode.critical.percent"),
        percent("inode.critical.percent", "Inode usage critical", "critical", "inode.warning.percent"),
        percent("memory.critical.percent", "Memory pressure critical", "critical", null),
        percent("pid.warning.percent", "PID usage warning", "warning", "pid.critical.percent"),
        percent("pid.critical.percent", "PID usage critical", "critical", "pid.warning.percent"),
        percent("conntrack.warning.percent", "Conntrack usage warning", "warning", "conntrack.critical.percent"),
        percent("conntrack.critical.percent", "Conntrack usage critical", "critical", "conntrack.warning.percent"),
        positive("disk.await.warning.ms", "Disk await latency warning", "ms", "warning"),
        positive("dns.latency.warning.ms", "DNS latency warning", "ms", "warning"),
        positive("api-server.latency.warning.ms", "API server latency warning", "ms", "warning"),
        positive("etcd.latency.warning.ms", "Etcd latency warning", "ms", "warning")
    );

    private final ClusterThresholdRepository repository;
    private final RcaConsoleProperties properties;

    @Autowired
    public ClusterThresholdService(ClusterThresholdRepository repository, RcaConsoleProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    private ClusterThresholdService(RcaConsoleProperties properties) {
        this.repository = null;
        this.properties = properties;
    }

    public static ClusterThresholdService defaultsOnly(RcaConsoleProperties properties) {
        return new ClusterThresholdService(properties);
    }

    public ClusterThresholdSettings settings(String clusterId) {
        Map<String, Double> defaults = defaults();
        Map<String, Double> overrides = overrides(clusterId);
        Map<String, Double> effective = new LinkedHashMap<>(defaults);
        effective.putAll(overrides);
        RcaConsoleProperties.Thresholds resolved = fromValues(effective);
        Map<String, Double> safeEffective = resolved.activeValues();
        return new ClusterThresholdSettings(
            clusterId,
            Map.copyOf(defaults),
            Map.copyOf(overrides),
            Map.copyOf(safeEffective),
            supportedKeys(),
            definitions(),
            repository == null ? null : repository.latestUpdatedAt(clusterId)
        );
    }

    public RcaConsoleProperties.Thresholds resolve(String clusterId) {
        if (clusterId == null || clusterId.isBlank()) {
            return properties.getThresholds();
        }
        return fromValues(settings(clusterId).effective());
    }

    public ClusterThresholdSettings replace(
        String clusterId,
        ClusterThresholdUpdateRequest request,
        String updatedBy
    ) {
        if (repository == null) {
            throw new IllegalStateException("cluster threshold repository is not available");
        }
        Map<String, Double> canonical = canonicalize(request == null ? Map.of() : request.thresholdsOrEmpty());
        validate(canonical);
        repository.replace(
            clusterId,
            canonical,
            request == null ? null : request.reason(),
            updatedBy
        );
        return settings(clusterId);
    }

    public ClusterThresholdSettings clear(String clusterId) {
        if (repository != null) {
            repository.deleteAll(clusterId);
        }
        return settings(clusterId);
    }

    public List<String> supportedKeys() {
        return List.copyOf(defaults().keySet());
    }

    public List<ThresholdDefinition> definitions() {
        List<ThresholdDefinition> result = new ArrayList<>();
        Map<String, ThresholdDefinition> byKey = new LinkedHashMap<>();
        DEFINITIONS.forEach(definition -> byKey.put(definition.key(), definition));
        for (String key : supportedKeys()) {
            result.add(byKey.getOrDefault(
                key,
                new ThresholdDefinition(key, key, "value", 0.0, null, "warning", null)
            ));
        }
        return List.copyOf(result);
    }

    public Map<String, Object> info() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("supported_keys", supportedKeys());
        result.put("definitions", definitions());
        result.put("default_values", defaults());
        result.put("cluster_override_enabled", repository != null);
        return result;
    }

    private Map<String, Double> overrides(String clusterId) {
        if (repository == null || clusterId == null || clusterId.isBlank()) {
            return Map.of();
        }
        return repository.values(clusterId);
    }

    private Map<String, Double> defaults() {
        return new LinkedHashMap<>(properties.getThresholds().activeValues());
    }

    private Map<String, Double> canonicalize(Map<String, Double> requested) {
        Map<String, Double> result = new LinkedHashMap<>();
        if (requested == null) {
            return result;
        }
        Map<String, String> canonicalByNormalized = new LinkedHashMap<>();
        for (String key : supportedKeys()) {
            canonicalByNormalized.put(normalize(key), key);
        }
        requested.forEach((key, value) -> {
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("threshold key must not be blank");
            }
            String canonical = canonicalByNormalized.get(normalize(key));
            if (canonical == null) {
                throw new IllegalArgumentException("unsupported threshold key: " + key);
            }
            if (value == null) {
                throw new IllegalArgumentException("threshold value must not be null: " + key);
            }
            result.put(canonical, value);
        });
        return result;
    }

    private void validate(Map<String, Double> overrides) {
        for (Map.Entry<String, Double> entry : overrides.entrySet()) {
            String key = entry.getKey();
            double value = entry.getValue();
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException("threshold value must be finite: " + key);
            }
            if (value <= 0) {
                throw new IllegalArgumentException("threshold value must be greater than zero: " + key);
            }
            if (key.endsWith(".percent") && value > 100) {
                throw new IllegalArgumentException("percent threshold must be <= 100: " + key);
            }
        }
        Map<String, Double> effective = new LinkedHashMap<>(defaults());
        effective.putAll(overrides);
        assertOrdered(effective, "disk.warning.percent", "disk.critical.percent");
        assertOrdered(effective, "inode.warning.percent", "inode.critical.percent");
        assertOrdered(effective, "pid.warning.percent", "pid.critical.percent");
        assertOrdered(effective, "conntrack.warning.percent", "conntrack.critical.percent");
    }

    private void assertOrdered(Map<String, Double> values, String warningKey, String criticalKey) {
        double warning = values.getOrDefault(warningKey, 0.0);
        double critical = values.getOrDefault(criticalKey, 0.0);
        if (critical < warning) {
            throw new IllegalArgumentException(
                criticalKey + " must be greater than or equal to " + warningKey
            );
        }
    }

    private RcaConsoleProperties.Thresholds fromValues(Map<String, Double> values) {
        RcaConsoleProperties.Thresholds thresholds = new RcaConsoleProperties.Thresholds();
        thresholds.setOverrides(values);
        return thresholds;
    }

    private String normalize(String key) {
        return key.trim()
            .toLowerCase(Locale.ROOT)
            .replace('_', '.')
            .replace('-', '.');
    }

    private static ThresholdDefinition percent(String key, String label, String severity, String pairedKey) {
        return new ThresholdDefinition(key, label, "percent", 0.0, 100.0, severity, pairedKey);
    }

    private static ThresholdDefinition positive(String key, String label, String unit, String severity) {
        return new ThresholdDefinition(key, label, unit, 0.0, null, severity, null);
    }
}
