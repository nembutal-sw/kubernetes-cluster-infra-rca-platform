package io.clusterinfra.rca.webconsole.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class CollectorEvidenceAdapterTests {
    private static final Set<String> AGENT_COLLECTORS = Set.of(
        "node", "kubernetes", "systemd", "kernel", "disk", "inode", "memory",
        "process", "network", "conntrack", "runtime", "kubelet", "cni", "dns"
    );
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CollectorEvidenceAdapter adapter = new CollectorEvidenceAdapter(objectMapper);

    @Test
    void normalizesAliasesAndPrimitiveTypesWithoutDroppingRawFields() {
        var adapted = adapter.adapt(Map.of(
            "disk", Map.of("root_usage_percent", "91.5%", "await_ms", "42"),
            "runtime", Map.of("socket_healthy", "false", "vendor_detail", "kept")
        ));

        @SuppressWarnings("unchecked")
        Map<String, Object> disk = (Map<String, Object>) adapted.collectors().get("disk");
        @SuppressWarnings("unchecked")
        Map<String, Object> runtime = (Map<String, Object>) adapted.collectors().get("runtime");

        assertThat(disk).containsEntry("root_usage_percent", "91.5%").containsEntry("usage_percent", 91.5d);
        assertThat(disk).containsEntry("await_ms", 42d);
        assertThat(runtime).containsEntry("runtime_socket_healthy", false).containsEntry("vendor_detail", "kept");
        assertThat(adapted.contract()).containsEntry("status", "valid");
    }

    @Test
    void reportsInvalidKnownFieldsAndUnknownCollectorsWithoutMutatingTheirValues() {
        var adapted = adapter.adapt(Map.of(
            "memory", Map.of("usage_percent", "not-a-number"),
            "vendor_probe", Map.of("latency", 10)
        ));

        @SuppressWarnings("unchecked")
        Map<String, CollectorEvidenceAdapter.CollectorContractResult> results =
            (Map<String, CollectorEvidenceAdapter.CollectorContractResult>) adapted.contract().get("collectors");
        assertThat(results.get("memory").status()).isEqualTo("invalid");
        assertThat(results.get("memory").invalidFields()).containsExactly(
            "usage_percent expected number at usage_percent"
        );
        assertThat(results.get("vendor_probe").status()).isEqualTo("unknown");
        assertThat(adapted.contract()).containsEntry("status", "invalid");
    }

    @Test
    void schemaCoversAgentCollectorsAndEveryRegressionFixtureCollector() throws IOException {
        @SuppressWarnings("unchecked")
        Map<String, Object> schemas = (Map<String, Object>) adapter.schemas().get("collectors");
        assertThat(schemas.keySet()).containsAll(AGENT_COLLECTORS);

        JsonNode scenarios = objectMapper.readTree(
            new ClassPathResource("analysis/rule-based-rca-regression-scenarios.json").getInputStream()
        );
        for (JsonNode scenario : scenarios) {
            List<String> names = new java.util.ArrayList<>();
            scenario.path("collectors").fieldNames().forEachRemaining(names::add);
            assertThat(schemas.keySet()).as(scenario.path("key").asText()).containsAll(names);
        }
    }
}
