package io.clusterinfra.rca.webconsole.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.clusterinfra.rca.webconsole.analysis.Signal;
import io.clusterinfra.rca.webconsole.analysis.SignalDetectionEngine;
import io.clusterinfra.rca.webconsole.analysis.detector.DiskPressureDetector;
import io.clusterinfra.rca.webconsole.catalog.OperationalCatalogService;
import io.clusterinfra.rca.webconsole.config.RcaConsoleProperties;
import io.clusterinfra.rca.webconsole.domain.RcaModels.ClusterCreateRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.ClusterThresholdUpdateRequest;
import io.clusterinfra.rca.webconsole.persistence.ClusterRepository;
import io.clusterinfra.rca.webconsole.persistence.ClusterThresholdRepository;
import static io.clusterinfra.rca.webconsole.TestSecurity.clusterRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class ClusterThresholdServiceTests {
    private final RcaConsoleProperties properties = new RcaConsoleProperties();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private ClusterRepository clusters;
    private ClusterThresholdService service;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
            "jdbc:h2:mem:cluster-thresholds-" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
            "sa",
            ""
        );
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        clusters = clusterRepository(jdbc);
        service = new ClusterThresholdService(new ClusterThresholdRepository(jdbc), properties);
    }

    @Test
    void storesCanonicalClusterOverridesAndReturnsEffectiveValues() {
        String clusterId = createCluster();

        var settings = service.replace(
            clusterId,
            new ClusterThresholdUpdateRequest(
                Map.of(
                    "disk_warning_percent", 93.0,
                    "disk-critical-percent", 95.0
                ),
                "noisy staging storage"
            ),
            "operator@example.com"
        );

        assertThat(settings.overrides())
            .containsEntry("disk.warning.percent", 93.0)
            .containsEntry("disk.critical.percent", 95.0);
        assertThat(settings.effective())
            .containsEntry("disk.warning.percent", 93.0)
            .containsEntry("disk.critical.percent", 95.0);
        assertThat(settings.definitions().stream().map(definition -> definition.key()).toList())
            .contains("disk.warning.percent", "disk.critical.percent", "dns.latency.warning.ms");
        assertThat(settings.updatedAt()).isNotNull();
    }

    @Test
    void rejectsUnknownOrUnsafeThresholdValues() {
        String clusterId = createCluster();

        assertThatThrownBy(() -> service.replace(
            clusterId,
            new ClusterThresholdUpdateRequest(Map.of("unknown.threshold", 1.0), null),
            "operator@example.com"
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("unsupported threshold key");

        assertThatThrownBy(() -> service.replace(
            clusterId,
            new ClusterThresholdUpdateRequest(Map.of("disk.warning.percent", 101.0), null),
            "operator@example.com"
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("percent threshold");

        assertThatThrownBy(() -> service.replace(
            clusterId,
            new ClusterThresholdUpdateRequest(Map.of("disk.warning.percent", 96.0), null),
            "operator@example.com"
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("disk.critical.percent");
    }

    @Test
    void signalDetectionUsesClusterSpecificThresholds() {
        String clusterId = createCluster();
        service.replace(
            clusterId,
            new ClusterThresholdUpdateRequest(
                Map.of("disk.warning.percent", 93.0, "disk.critical.percent", 95.0),
                "raise disk pressure thresholds for test cluster"
            ),
            "operator@example.com"
        );
        SignalDetectionEngine engine = new SignalDetectionEngine(
            List.of(new DiskPressureDetector()),
            properties,
            objectMapper,
            OperationalCatalogService.defaultService(),
            service
        );

        List<Signal> defaultSignals = engine.detect(Map.of("disk", Map.of("disk_usage_percent", 92.0)));
        List<Signal> clusterSignals = engine.detect(clusterId, Map.of("disk", Map.of("disk_usage_percent", 92.0)));
        List<Signal> criticalSignals = engine.detect(clusterId, Map.of("disk", Map.of("disk_usage_percent", 96.0)));

        assertThat(defaultSignals).extracting(Signal::name).contains("disk_usage_critical");
        assertThat(clusterSignals).isEmpty();
        assertThat(criticalSignals).extracting(Signal::name).contains("disk_usage_critical");
        assertThat(criticalSignals.getFirst().threshold()).isEqualTo(95.0);
    }

    private String createCluster() {
        return clusters.create(new ClusterCreateRequest("threshold-cluster", "test", null)).clusterId();
    }
}
