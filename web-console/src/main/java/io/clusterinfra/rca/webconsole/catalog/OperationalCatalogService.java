package io.clusterinfra.rca.webconsole.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.clusterinfra.rca.webconsole.catalog.OperationalCatalog.ActionDefinition;
import io.clusterinfra.rca.webconsole.catalog.OperationalCatalog.ActionPlanDefinition;
import io.clusterinfra.rca.webconsole.catalog.OperationalCatalog.CatalogDocument;
import io.clusterinfra.rca.webconsole.catalog.OperationalCatalog.CollectorDefinition;
import io.clusterinfra.rca.webconsole.catalog.OperationalCatalog.CollectorSelection;
import io.clusterinfra.rca.webconsole.catalog.OperationalCatalog.RuleDefinition;
import io.clusterinfra.rca.webconsole.config.RcaConsoleProperties;
import io.clusterinfra.rca.webconsole.domain.RcaModels.ActionPlan;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class OperationalCatalogService {
    private static final String SUPPORTED_SCHEMA = "rca-catalog/v1";
    private static final Pattern KEY_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9_-]*$");
    private static final int MAX_PREVIEW_DIFFS = 200;
    private final ObjectMapper objectMapper;
    private final OperationalCatalog catalog;
    private final boolean externalOverrideActive;

    @Autowired
    public OperationalCatalogService(ObjectMapper objectMapper, RcaConsoleProperties properties) {
        this(objectMapper, properties, new DefaultResourceLoader());
    }

    OperationalCatalogService(
        ObjectMapper objectMapper,
        RcaConsoleProperties properties,
        ResourceLoader resourceLoader
    ) {
        this.objectMapper = objectMapper;
        this.externalOverrideActive = !properties.getCatalog().getExternalPath().isBlank();
        this.catalog = load(objectMapper, properties.getCatalog(), resourceLoader);
    }

    public static OperationalCatalogService defaultService() {
        return new OperationalCatalogService(new ObjectMapper(), new RcaConsoleProperties());
    }

    public OperationalCatalog catalog() {
        return catalog;
    }

    public List<String> collectorsForAlert(String alertName) {
        return catalog.collectorsForAlert(alertName).stream()
            .filter(this::collectorEnabled)
            .distinct()
            .toList();
    }

    public Optional<ActionDefinition> action(String actionKey) {
        if (actionKey == null || actionKey.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(catalog.actions().get(normalize(actionKey)));
    }

    public List<Map.Entry<String, ActionDefinition>> recommendedActions(
        String alertName,
        Set<String> signalNames,
        Set<String> components
    ) {
        Set<String> names = normalizeSet(signalNames);
        Set<String> componentNames = normalizeSet(components);
        String normalizedAlert = alertName == null ? "" : alertName.trim();
        return catalog.actions().entrySet().stream()
            .filter(entry -> matches(entry.getValue(), normalizedAlert, names, componentNames))
            .toList();
    }

    public boolean detectorEnabled(String detectorId) {
        return catalog.detectorEnabled(detectorId);
    }

    public ActionPlan actionPlan(String actionKey) {
        return action(actionKey)
            .map(ActionDefinition::plan)
            .map(this::toActionPlan)
            .orElse(null);
    }

    public Map<String, Object> info() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schema_version", catalog.schemaVersion());
        result.put("version", catalog.version());
        result.put("source", catalog.source());
        result.put("checksum", catalog.checksum());
        result.put("external_override_active", externalOverrideActive);
        result.put("action_plan_execution_enabled", false);
        result.put("collector_count", catalog.collectors().size());
        result.put("action_count", catalog.actions().size());
        result.put("rule_count", catalog.rules().size());
        result.put("default_collectors", catalog.defaultCollectors());
        return result;
    }

    public Map<String, Object> detail() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("summary", info());
        result.put("collectors", catalog.collectors());
        result.put("collector_selection", catalog.collectorSelection());
        result.put("actions", catalog.actions());
        result.put("rules", catalog.rules());
        return result;
    }

    public Map<String, Object> previewOverride(String overrideJson) {
        if (overrideJson == null || overrideJson.isBlank()) {
            return previewResult(false, "override_json must not be blank", Map.of(), List.of(), false);
        }
        try {
            CatalogDocument override = objectMapper.readValue(overrideJson, CatalogDocument.class);
            CatalogDocument candidateDocument = merge(document(catalog), override);
            String checksum = checksum(objectMapper, candidateDocument);
            OperationalCatalog candidate = toCatalog(candidateDocument, catalog.source() + ",preview", checksum);
            validate(candidate);
            List<Map<String, Object>> diff = diff(document(catalog), candidateDocument);
            return previewResult(
                true,
                diff.isEmpty() ? "Override is valid. No catalog changes detected." : "Override is valid.",
                summary(candidate, externalOverrideActive),
                diff,
                diff.size() >= MAX_PREVIEW_DIFFS
            );
        } catch (IOException | IllegalArgumentException | IllegalStateException exception) {
            return previewResult(
                false,
                exception.getMessage() == null ? "Invalid catalog override JSON." : exception.getMessage(),
                Map.of(),
                List.of(),
                false
            );
        }
    }

    private boolean collectorEnabled(String key) {
        CollectorDefinition collector = catalog.collectors().get(key);
        return collector == null || collector.enabledOrDefault();
    }

    private boolean matches(
        ActionDefinition action,
        String alertName,
        Set<String> signalNames,
        Set<String> components
    ) {
        var triggers = action.triggersOrEmpty();
        if (triggers.alwaysOrDefault()) {
            return true;
        }
        if (triggers.requiresSignalsOrDefault() && !signalNames.isEmpty()) {
            return true;
        }
        if (triggers.alertNamesAnyOrEmpty().stream().anyMatch(alertName::equals)) {
            return true;
        }
        if (triggers.componentsAnyOrEmpty().stream().map(this::normalize).anyMatch(components::contains)) {
            return true;
        }
        return triggers.signalNamesAnyOrEmpty().stream().map(this::normalize).anyMatch(signalNames::contains);
    }

    private ActionPlan toActionPlan(ActionPlanDefinition plan) {
        if (plan == null || plan.commandKey() == null || plan.commandKey().isBlank()) {
            return null;
        }
        return new ActionPlan(
            plan.commandKey(),
            plan.parametersOrEmpty(),
            plan.commandPreviewOrEmpty(),
            plan.yamlPatch(),
            plan.executableOrDefault(),
            plan.timeoutSecondsOrDefault()
        );
    }

    private OperationalCatalog load(
        ObjectMapper objectMapper,
        RcaConsoleProperties.Catalog properties,
        ResourceLoader resourceLoader
    ) {
        LoadedCatalog base = read(objectMapper, resourceLoader.getResource(properties.getClasspathLocation()));
        LoadedCatalog loaded = base;
        if (!properties.getExternalPath().isBlank()) {
            LoadedCatalog override = read(objectMapper, externalResource(properties.getExternalPath(), resourceLoader));
            loaded = new LoadedCatalog(merge(base.document(), override.document()), base.source() + "," + override.source());
        }
        String checksum = checksum(objectMapper, loaded.document());
        OperationalCatalog result = toCatalog(loaded.document(), loaded.source(), checksum);
        validate(result);
        return result;
    }

    private Resource externalResource(String value, ResourceLoader resourceLoader) {
        if (value.startsWith("classpath:") || value.startsWith("file:")) {
            return resourceLoader.getResource(value);
        }
        return new FileSystemResource(Path.of(value));
    }

    private LoadedCatalog read(ObjectMapper objectMapper, Resource resource) {
        if (!resource.exists()) {
            throw new IllegalStateException("RCA catalog resource does not exist: " + resource.getDescription());
        }
        try (InputStream input = resource.getInputStream()) {
            CatalogDocument document = objectMapper.readValue(input, CatalogDocument.class);
            return new LoadedCatalog(document, resource.getDescription());
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to load RCA catalog: " + resource.getDescription(), exception);
        }
    }

    private CatalogDocument merge(CatalogDocument base, CatalogDocument override) {
        return new CatalogDocument(
            firstText(override.schemaVersion(), base.schemaVersion()),
            firstText(override.version(), base.version()),
            mergeMap(base.collectors(), override.collectors(), this::mergeCollector),
            mergeSelection(base.collectorSelection(), override.collectorSelection()),
            mergeMap(base.actions(), override.actions(), this::mergeAction),
            mergeMap(base.rules(), override.rules(), this::mergeRule)
        );
    }

    private CollectorDefinition mergeCollector(CollectorDefinition base, CollectorDefinition override) {
        return new CollectorDefinition(
            firstText(override.description(), base.description()),
            override.enabled() == null ? base.enabled() : override.enabled(),
            override.permissionModes() == null ? base.permissionModes() : override.permissionModes()
        );
    }

    private CollectorSelection mergeSelection(CollectorSelection base, CollectorSelection override) {
        Map<String, List<String>> alerts = new LinkedHashMap<>();
        if (base != null) {
            alerts.putAll(base.alerts());
        }
        if (override != null) {
            alerts.putAll(override.alerts());
        }
        List<String> defaults = override != null && !override.defaultCollectors().isEmpty()
            ? override.defaultCollectors()
            : base == null ? List.of() : base.defaultCollectors();
        return new CollectorSelection(defaults, alerts);
    }

    private ActionDefinition mergeAction(ActionDefinition base, ActionDefinition override) {
        return new ActionDefinition(
            firstText(override.action(), base.action()),
            firstText(override.reason(), base.reason()),
            override.policy() == null ? base.policy() : override.policy(),
            firstText(override.automationMode(), base.automationMode()),
            override.risks() == null ? base.risks() : override.risks(),
            override.triggers() == null ? base.triggers() : override.triggers(),
            mergePlan(base.plan(), override.plan())
        );
    }

    private ActionPlanDefinition mergePlan(ActionPlanDefinition base, ActionPlanDefinition override) {
        if (override == null) {
            return base;
        }
        if (base == null) {
            return override;
        }
        return new ActionPlanDefinition(
            firstText(override.commandKey(), base.commandKey()),
            override.parameters() == null ? base.parameters() : override.parameters(),
            override.commandPreview() == null ? base.commandPreview() : override.commandPreview(),
            firstText(override.yamlPatch(), base.yamlPatch()),
            override.executable() == null ? base.executable() : override.executable(),
            override.timeoutSeconds() == null ? base.timeoutSeconds() : override.timeoutSeconds()
        );
    }

    private RuleDefinition mergeRule(RuleDefinition base, RuleDefinition override) {
        return new RuleDefinition(
            firstText(override.detector(), base.detector()),
            override.enabled() == null ? base.enabled() : override.enabled(),
            firstText(override.component(), base.component()),
            override.signals() == null ? base.signals() : override.signals()
        );
    }

    private <T> Map<String, T> mergeMap(
        Map<String, T> base,
        Map<String, T> override,
        java.util.function.BinaryOperator<T> merge
    ) {
        Map<String, T> result = new LinkedHashMap<>();
        if (base != null) {
            result.putAll(base);
        }
        if (override != null) {
            override.forEach((key, value) -> result.merge(key, value, merge));
        }
        return result;
    }

    private OperationalCatalog toCatalog(CatalogDocument document, String source, String checksum) {
        return new OperationalCatalog(
            document.schemaVersion(),
            document.version(),
            source,
            checksum,
            document.collectors(),
            document.collectorSelection(),
            document.actions(),
            document.rules()
        );
    }

    private CatalogDocument document(OperationalCatalog catalog) {
        return new CatalogDocument(
            catalog.schemaVersion(),
            catalog.version(),
            catalog.collectors(),
            catalog.collectorSelection(),
            catalog.actions(),
            catalog.rules()
        );
    }

    private Map<String, Object> summary(OperationalCatalog catalog, boolean overrideActive) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schema_version", catalog.schemaVersion());
        result.put("version", catalog.version());
        result.put("source", catalog.source());
        result.put("checksum", catalog.checksum());
        result.put("external_override_active", overrideActive);
        result.put("action_plan_execution_enabled", false);
        result.put("collector_count", catalog.collectors().size());
        result.put("action_count", catalog.actions().size());
        result.put("rule_count", catalog.rules().size());
        result.put("default_collectors", catalog.defaultCollectors());
        return result;
    }

    private Map<String, Object> previewResult(
        boolean valid,
        String message,
        Map<String, Object> summary,
        List<Map<String, Object>> diff,
        boolean diffTruncated
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("valid", valid);
        result.put("message", message);
        result.put("summary", summary);
        result.put("diff", diff);
        result.put("diff_count", diff.size());
        result.put("diff_truncated", diffTruncated);
        return result;
    }

    private List<Map<String, Object>> diff(CatalogDocument current, CatalogDocument proposed) {
        List<Map<String, Object>> result = new ArrayList<>();
        JsonNode currentNode = objectMapper.convertValue(current, JsonNode.class);
        JsonNode proposedNode = objectMapper.convertValue(proposed, JsonNode.class);
        appendDiff("", currentNode, proposedNode, result);
        return result;
    }

    private void appendDiff(String path, JsonNode current, JsonNode proposed, List<Map<String, Object>> result) {
        if (result.size() >= MAX_PREVIEW_DIFFS || nodesEqual(current, proposed)) {
            return;
        }
        if (current == null || current.isMissingNode()) {
            result.add(diffEntry(path, "added", null, proposed));
            return;
        }
        if (proposed == null || proposed.isMissingNode()) {
            result.add(diffEntry(path, "removed", current, null));
            return;
        }
        if (current.isObject() && proposed.isObject()) {
            Set<String> fields = new LinkedHashSet<>();
            current.fieldNames().forEachRemaining(fields::add);
            proposed.fieldNames().forEachRemaining(fields::add);
            for (String field : fields) {
                if (result.size() >= MAX_PREVIEW_DIFFS) {
                    return;
                }
                appendDiff(path(path, field), current.path(field), proposed.path(field), result);
            }
            return;
        }
        if (current.isArray() || proposed.isArray()) {
            result.add(diffEntry(path, "changed", current, proposed));
            return;
        }
        result.add(diffEntry(path, "changed", current, proposed));
    }

    private boolean nodesEqual(JsonNode current, JsonNode proposed) {
        if (current == null || proposed == null) {
            return current == proposed;
        }
        return current.equals(proposed);
    }

    private Map<String, Object> diffEntry(String path, String changeType, JsonNode current, JsonNode proposed) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", path == null || path.isBlank() ? "/" : path);
        result.put("change_type", changeType);
        result.put("current_value", jsonValue(current));
        result.put("proposed_value", jsonValue(proposed));
        return result;
    }

    private Object jsonValue(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) {
            return null;
        }
        return objectMapper.convertValue(value, Object.class);
    }

    private String path(String parent, String field) {
        String escaped = field.replace("~", "~0").replace("/", "~1");
        return parent == null || parent.isBlank() ? "/" + escaped : parent + "/" + escaped;
    }

    private void validate(OperationalCatalog catalog) {
        List<String> errors = new ArrayList<>();
        if (!SUPPORTED_SCHEMA.equals(catalog.schemaVersion())) {
            errors.add("unsupported schema_version=" + catalog.schemaVersion());
        }
        if (catalog.collectors().isEmpty()) {
            errors.add("collectors must not be empty");
        }
        if (catalog.actions().isEmpty()) {
            errors.add("actions must not be empty");
        }
        if (catalog.rules().isEmpty()) {
            errors.add("rules must not be empty");
        }
        catalog.collectors().keySet().forEach(key -> validateKey(errors, "collector", key));
        catalog.actions().keySet().forEach(key -> validateKey(errors, "action", key));
        catalog.rules().keySet().forEach(key -> validateKey(errors, "rule", key));
        validateCollectors(errors, "collector_selection.default_collectors", catalog.defaultCollectors(), catalog.collectors().keySet());
        catalog.collectorSelection().alerts().forEach((alert, collectors) ->
            validateCollectors(errors, "collector_selection.alerts." + alert, collectors, catalog.collectors().keySet())
        );
        catalog.actions().forEach((key, action) -> validateAction(errors, key, action));
        if (!catalog.actions().containsKey("manual_investigation")) {
            errors.add("actions.manual_investigation is required");
        }
        if (!catalog.actions().containsKey("collect_more_evidence")) {
            errors.add("actions.collect_more_evidence is required");
        }
        if (!errors.isEmpty()) {
            throw new IllegalStateException("Invalid RCA operational catalog: " + String.join("; ", errors));
        }
    }

    private void validateCollectors(
        List<String> errors,
        String location,
        List<String> values,
        Set<String> knownCollectors
    ) {
        if (values == null || values.isEmpty()) {
            errors.add(location + " must not be empty");
            return;
        }
        values.forEach(value -> {
            if (!knownCollectors.contains(value)) {
                errors.add(location + " references unknown collector " + value);
            }
        });
    }

    private void validateAction(List<String> errors, String key, ActionDefinition action) {
        if (action.policy() == null) {
            errors.add("actions." + key + ".policy is required");
        }
        if (action.automationModeOrDefault().isBlank()) {
            errors.add("actions." + key + ".automation_mode is required");
        }
        ActionPlanDefinition plan = action.plan();
        if (plan != null && plan.executableOrDefault()) {
            errors.add("actions." + key + ".plan.executable must be false; direct agent mutation is disabled");
        }
    }

    private void validateKey(List<String> errors, String kind, String key) {
        if (key == null || !KEY_PATTERN.matcher(key).matches()) {
            errors.add(kind + " key is invalid: " + key);
        }
    }

    private String checksum(ObjectMapper objectMapper, CatalogDocument document) {
        try {
            byte[] payload = objectMapper.writeValueAsBytes(document);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(payload));
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Failed to calculate RCA catalog checksum", exception);
        }
    }

    private String firstText(String candidate, String fallback) {
        return candidate == null || candidate.isBlank() ? fallback : candidate;
    }

    private Set<String> normalizeSet(Set<String> values) {
        Set<String> result = new LinkedHashSet<>();
        if (values != null) {
            values.stream().map(this::normalize).filter(value -> !value.isBlank()).forEach(result::add);
        }
        return result;
    }

    private String normalize(String value) {
        return value == null
            ? ""
            : value.trim().toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_]+", "_").replaceAll("^_+|_+$", "");
    }

    private record LoadedCatalog(CatalogDocument document, String source) {
    }
}
