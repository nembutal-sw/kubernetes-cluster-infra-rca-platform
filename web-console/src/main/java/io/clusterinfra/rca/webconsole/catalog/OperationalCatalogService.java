package io.clusterinfra.rca.webconsole.catalog;

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
    private final OperationalCatalog catalog;

    @Autowired
    public OperationalCatalogService(ObjectMapper objectMapper, RcaConsoleProperties properties) {
        this(objectMapper, properties, new DefaultResourceLoader());
    }

    OperationalCatalogService(
        ObjectMapper objectMapper,
        RcaConsoleProperties properties,
        ResourceLoader resourceLoader
    ) {
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
        result.put("collector_count", catalog.collectors().size());
        result.put("action_count", catalog.actions().size());
        result.put("rule_count", catalog.rules().size());
        result.put("default_collectors", catalog.defaultCollectors());
        return result;
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
