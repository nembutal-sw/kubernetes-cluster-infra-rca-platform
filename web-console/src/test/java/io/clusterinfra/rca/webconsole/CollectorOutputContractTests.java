package io.clusterinfra.rca.webconsole;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class CollectorOutputContractTests {
    private static final Set<String> KNOWN_COLLECTORS = Set.of(
        "_meta",
        "alertmanager",
        "node",
        "kubernetes",
        "systemd",
        "runtime",
        "containerd",
        "kubelet",
        "kernel",
        "disk",
        "inode",
        "memory",
        "process",
        "network",
        "conntrack",
        "cni",
        "dns",
        "etcd",
        "ebpf"
    );
    private static final Set<String> NOISY_WEB_FIELDS = Set.of(
        "user_agent",
        "browser",
        "browser_version",
        "client_os",
        "os_version"
    );
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void rcaScenarioFixturesUseKnownCollectorsAndAvoidWebClientNoise() throws IOException {
        JsonNode scenarios = objectMapper.readTree(
            new ClassPathResource("analysis/rule-based-rca-regression-scenarios.json").getInputStream()
        );

        for (JsonNode scenario : scenarios) {
            String key = scenario.path("key").asText();
            JsonNode collectors = scenario.path("collectors");
            assertThat(collectors.isObject()).as(key + " collectors object").isTrue();
            collectors.fieldNames().forEachRemaining(collector -> {
                assertThat(KNOWN_COLLECTORS).as(key + " collector " + collector).contains(collector);
                assertNoNoisyWebFields(key, collector, collectors.path(collector));
            });
        }
    }

    private void assertNoNoisyWebFields(String scenario, String collector, JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return;
        }
        if (node.isObject()) {
            node.fieldNames().forEachRemaining(field -> {
                assertThat(NOISY_WEB_FIELDS).as(scenario + " " + collector + " noisy field " + field)
                    .doesNotContain(field);
                assertNoNoisyWebFields(scenario, collector, node.path(field));
            });
        } else if (node.isArray()) {
            node.forEach(item -> assertNoNoisyWebFields(scenario, collector, item));
        }
    }
}
