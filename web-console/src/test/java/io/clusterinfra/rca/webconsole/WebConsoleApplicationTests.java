package io.clusterinfra.rca.webconsole;

import static org.assertj.core.api.Assertions.assertThat;

import io.clusterinfra.rca.webconsole.service.RuleBasedRcaAnalyzer;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:context-tests;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.ai.model.chat=none"
})
class WebConsoleApplicationTests {
    @Autowired
    private RuleBasedRcaAnalyzer analyzer;

    @Test
    void contextLoads() {
    }

    @Test
    void scheduledHealthyEvidenceDoesNotProduceIncidentSignals() {
        Map<String, Object> healthy = Map.of(
            "node", Map.of("status", "ok"),
            "disk", Map.of("root_usage_percent", 20.0),
            "inode", Map.of("inode_usage_percent", 25.0),
            "memory", Map.of("usage_percent", 40.0),
            "conntrack", Map.of("count", 10, "max", 100),
            "systemd", Map.of("failed_units", List.of())
        );

        assertThat(analyzer.hasActionableSignals(healthy)).isFalse();
        assertThat(analyzer.hasActionableSignals(Map.of(
            "disk", Map.of("root_usage_percent", 96.0)
        ))).isTrue();
    }
}
