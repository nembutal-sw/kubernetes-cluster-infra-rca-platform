package io.clusterinfra.rca.webconsole.catalog;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.clusterinfra.rca.webconsole.domain.RcaModels.PolicyLevel;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record OperationalCatalog(
    String schemaVersion,
    String version,
    String source,
    String checksum,
    Map<String, CollectorDefinition> collectors,
    CollectorSelection collectorSelection,
    Map<String, ActionDefinition> actions,
    Map<String, RuleDefinition> rules
) {
    public OperationalCatalog {
        schemaVersion = stringOrDefault(schemaVersion, "rca-catalog/v1");
        version = stringOrDefault(version, "dev");
        source = stringOrDefault(source, "unknown");
        checksum = stringOrDefault(checksum, "");
        collectors = immutableMap(collectors);
        collectorSelection = collectorSelection == null
            ? new CollectorSelection(List.of(), Map.of())
            : collectorSelection;
        actions = immutableMap(actions);
        rules = immutableMap(rules);
    }

    public List<String> defaultCollectors() {
        return collectorSelection.defaultCollectors();
    }

    public List<String> collectorsForAlert(String alertName) {
        if (alertName == null || alertName.isBlank()) {
            return defaultCollectors();
        }
        return collectorSelection.alerts().getOrDefault(alertName.trim(), defaultCollectors());
    }

    public boolean detectorEnabled(String detectorId) {
        RuleDefinition rule = rules.get(detectorId);
        return rule == null || rule.enabledOrDefault();
    }

    private static String stringOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static <T> Map<String, T> immutableMap(Map<String, T> values) {
        return values == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CatalogDocument(
        @JsonProperty("schema_version") String schemaVersion,
        String version,
        Map<String, CollectorDefinition> collectors,
        @JsonProperty("collector_selection") CollectorSelection collectorSelection,
        Map<String, ActionDefinition> actions,
        Map<String, RuleDefinition> rules
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CollectorDefinition(
        String description,
        Boolean enabled,
        @JsonProperty("permission_modes") List<String> permissionModes
    ) {
        public boolean enabledOrDefault() {
            return enabled == null || enabled;
        }

        public List<String> permissionModesOrEmpty() {
            return permissionModes == null ? List.of() : List.copyOf(permissionModes);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CollectorSelection(
        @JsonProperty("default_collectors") List<String> defaultCollectors,
        Map<String, List<String>> alerts
    ) {
        public CollectorSelection {
            defaultCollectors = defaultCollectors == null ? List.of() : List.copyOf(defaultCollectors);
            alerts = alerts == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(alerts));
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ActionDefinition(
        String action,
        String reason,
        PolicyLevel policy,
        @JsonProperty("automation_mode") String automationMode,
        List<String> risks,
        ActionTriggers triggers,
        ActionPlanDefinition plan
    ) {
        public List<String> risksOrEmpty() {
            return risks == null ? List.of() : List.copyOf(risks);
        }

        public String automationModeOrDefault() {
            return automationMode == null || automationMode.isBlank() ? "manual" : automationMode.trim();
        }

        public ActionTriggers triggersOrEmpty() {
            return triggers == null ? new ActionTriggers(false, false, List.of(), List.of(), List.of()) : triggers;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ActionTriggers(
        Boolean always,
        @JsonProperty("requires_signals") Boolean requiresSignals,
        @JsonProperty("components_any") List<String> componentsAny,
        @JsonProperty("signal_names_any") List<String> signalNamesAny,
        @JsonProperty("alert_names_any") List<String> alertNamesAny
    ) {
        public boolean alwaysOrDefault() {
            return Boolean.TRUE.equals(always);
        }

        public boolean requiresSignalsOrDefault() {
            return Boolean.TRUE.equals(requiresSignals);
        }

        public List<String> componentsAnyOrEmpty() {
            return componentsAny == null ? List.of() : List.copyOf(componentsAny);
        }

        public List<String> signalNamesAnyOrEmpty() {
            return signalNamesAny == null ? List.of() : List.copyOf(signalNamesAny);
        }

        public List<String> alertNamesAnyOrEmpty() {
            return alertNamesAny == null ? List.of() : List.copyOf(alertNamesAny);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ActionPlanDefinition(
        @JsonProperty("command_key") String commandKey,
        Map<String, String> parameters,
        @JsonProperty("command_preview") List<String> commandPreview,
        @JsonProperty("yaml_patch") String yamlPatch,
        Boolean executable,
        @JsonProperty("timeout_seconds") Integer timeoutSeconds
    ) {
        public Map<String, String> parametersOrEmpty() {
            return parameters == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(parameters));
        }

        public List<String> commandPreviewOrEmpty() {
            return commandPreview == null ? List.of() : List.copyOf(commandPreview);
        }

        public boolean executableOrDefault() {
            return Boolean.TRUE.equals(executable);
        }

        public int timeoutSecondsOrDefault() {
            return timeoutSeconds == null ? 0 : Math.max(0, timeoutSeconds);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RuleDefinition(
        String detector,
        Boolean enabled,
        String component,
        List<String> signals
    ) {
        public boolean enabledOrDefault() {
            return enabled == null || enabled;
        }

        public List<String> signalsOrEmpty() {
            return signals == null ? List.of() : List.copyOf(signals);
        }
    }
}
