package io.clusterinfra.rca.webconsole.service;

import io.clusterinfra.rca.webconsole.config.RcaConsoleProperties;
import io.clusterinfra.rca.webconsole.domain.RcaModels.ClusterThresholdSettings;
import io.clusterinfra.rca.webconsole.domain.RcaModels.ClusterThresholdUpdateRequest;
import io.clusterinfra.rca.webconsole.persistence.ClusterThresholdRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class ClusterThresholdService {
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

    public Map<String, Object> info() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("supported_keys", supportedKeys());
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
}
