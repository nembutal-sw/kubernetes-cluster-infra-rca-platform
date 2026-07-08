package io.clusterinfra.rca.webconsole.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.clusterinfra.rca.webconsole.analysis.AnalysisContext;
import io.clusterinfra.rca.webconsole.analysis.SignalDetectionEngine;
import io.clusterinfra.rca.webconsole.analysis.detector.DiskPressureDetector;
import io.clusterinfra.rca.webconsole.config.RcaConsoleProperties;
import io.clusterinfra.rca.webconsole.domain.RcaModels.PolicyLevel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;

class OperationalCatalogServiceTests {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void defaultCatalogExposesCollectorsActionsRulesAndMetadata() {
        OperationalCatalogService service = OperationalCatalogService.defaultService();

        assertThat(service.catalog().schemaVersion()).isEqualTo("rca-catalog/v1");
        assertThat(service.collectorsForAlert("DiskPressure"))
            .containsExactly("node", "disk", "inode", "kernel", "systemd");
        assertThat(service.action("restart_kubelet")).hasValueSatisfying(action -> {
            assertThat(action.policy()).isEqualTo(PolicyLevel.APPROVAL_REQUIRED);
            assertThat(action.risksOrEmpty()).contains("node_agent_disruption");
        });
        assertThat(service.actionPlan("restart_kubelet")).satisfies(plan -> {
            assertThat(plan.commandKey()).isEqualTo("restart_systemd_unit");
            assertThat(plan.parameters()).containsEntry("unit", "kubelet");
            assertThat(plan.executable()).isFalse();
        });
        assertThat(service.detectorEnabled("disk-pressure")).isTrue();
        assertThat(service.info())
            .containsEntry("schema_version", "rca-catalog/v1")
            .containsEntry("action_plan_execution_enabled", false)
            .containsEntry("external_override_active", false)
            .containsEntry("collector_count", service.catalog().collectors().size())
            .containsEntry("action_count", service.catalog().actions().size())
            .containsEntry("rule_count", service.catalog().rules().size());
        assertThat(String.valueOf(service.info().get("checksum"))).hasSize(64);
        assertThat(service.detail())
            .containsKeys("summary", "collectors", "collector_selection", "actions", "rules");
    }

    @Test
    void overridePreviewReturnsValidationResultAndDiffWithoutApplyingCatalog() {
        OperationalCatalogService service = OperationalCatalogService.defaultService();

        Map<String, Object> preview = service.previewOverride("""
            {
              "schema_version": "rca-catalog/v1",
              "version": "preview-test",
              "rules": {
                "disk-pressure": {"enabled": false}
              }
            }
            """);
        JsonNode previewJson = objectMapper.valueToTree(preview);

        assertThat(previewJson.path("valid").asBoolean()).isTrue();
        assertThat(previewJson.path("message").asText()).isEqualTo("Override is valid.");
        assertThat(previewJson.path("summary").path("version").asText()).isEqualTo("preview-test");
        assertThat(previewJson.path("summary").path("action_plan_execution_enabled").asBoolean()).isFalse();
        assertThat(previewJson.path("diff").toString())
            .contains("/version")
            .contains("/rules/disk-pressure/enabled")
            .contains("\"proposed_value\":false");
        assertThat(service.detectorEnabled("disk-pressure")).isTrue();
    }

    @Test
    void overridePreviewRejectsUnsafeExecutableActionPlan() {
        OperationalCatalogService service = OperationalCatalogService.defaultService();

        Map<String, Object> preview = service.previewOverride("""
            {
              "schema_version": "rca-catalog/v1",
              "actions": {
                "restart_kubelet": {
                  "plan": {
                    "executable": true
                  }
                }
              }
            }
            """);
        JsonNode previewJson = objectMapper.valueToTree(preview);

        assertThat(previewJson.path("valid").asBoolean()).isFalse();
        assertThat(previewJson.path("message").asText()).contains("plan.executable must be false");
        assertThat(previewJson.path("diff")).isEmpty();
    }

    @Test
    void externalCatalogOverrideCanTuneSelectionActionsAndRules(@TempDir Path tempDir) throws Exception {
        Path override = tempDir.resolve("catalog-override.json");
        Files.writeString(override, """
            {
              "schema_version": "rca-catalog/v1",
              "version": "override-test",
              "collector_selection": {
                "alerts": {
                  "DiskPressure": ["node", "disk"]
                }
              },
              "actions": {
                "inspect_storage_state": {
                  "action": "Inspect only storage evidence selected by the override.",
                  "triggers": {"alert_names_any": ["CustomStorageAlert"]}
                }
              },
              "rules": {
                "disk-pressure": {"enabled": false}
              }
            }
            """);
        RcaConsoleProperties properties = new RcaConsoleProperties();
        properties.getCatalog().setExternalPath(override.toString());

        OperationalCatalogService service = new OperationalCatalogService(
            objectMapper,
            properties,
            new DefaultResourceLoader()
        );

        assertThat(service.catalog().version()).isEqualTo("override-test");
        assertThat(service.info()).containsEntry("external_override_active", true);
        assertThat(service.collectorsForAlert("DiskPressure")).containsExactly("node", "disk");
        assertThat(service.detectorEnabled("disk-pressure")).isFalse();
        assertThat(service.action("inspect_storage_state")).hasValueSatisfying(action ->
            assertThat(action.action()).isEqualTo("Inspect only storage evidence selected by the override.")
        );
        assertThat(service.recommendedActions("CustomStorageAlert", Set.of(), Set.of()))
            .extracting(Map.Entry::getKey)
            .contains("inspect_storage_state");

        SignalDetectionEngine engine = new SignalDetectionEngine(
            List.of(new DiskPressureDetector()),
            properties,
            objectMapper,
            service
        );
        AnalysisContext context = AnalysisContext.create(
            Map.of("disk", Map.of("root_usage_percent", 99.0)),
            properties.getThresholds(),
            objectMapper
        );
        assertThat(new DiskPressureDetector().detect(context)).isNotEmpty();
        assertThat(engine.detect(Map.of("disk", Map.of("root_usage_percent", 99.0)))).isEmpty();
    }

    @Test
    void executableActionPlansAreRejectedAtCatalogLoad(@TempDir Path tempDir) throws Exception {
        Path override = tempDir.resolve("unsafe-catalog.json");
        Files.writeString(override, """
            {
              "schema_version": "rca-catalog/v1",
              "actions": {
                "collect_more_evidence": {
                  "plan": {
                    "command_key": "unsafe",
                    "command_preview": ["systemctl restart kubelet"],
                    "executable": true,
                    "timeout_seconds": 10
                  }
                }
              }
            }
            """);
        RcaConsoleProperties properties = new RcaConsoleProperties();
        properties.getCatalog().setExternalPath(override.toString());

        assertThatThrownBy(() -> new OperationalCatalogService(objectMapper, properties, new DefaultResourceLoader()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("plan.executable must be false");
    }
}
