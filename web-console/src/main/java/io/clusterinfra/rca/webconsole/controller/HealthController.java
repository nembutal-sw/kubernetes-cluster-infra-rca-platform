package io.clusterinfra.rca.webconsole.controller;

import io.clusterinfra.rca.webconsole.config.RcaConsoleProperties;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class HealthController {
    private final JdbcTemplate jdbc;
    private final RcaConsoleProperties properties;

    public HealthController(DataSource dataSource, RcaConsoleProperties properties) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.properties = properties;
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }

    @GetMapping("/health/ready")
    public Map<String, String> readiness() {
        try {
            jdbc.queryForObject("SELECT 1", Integer.class);
            return Map.of(
                "status", "ok",
                "database", "reachable",
                "llm_provider", properties.getLlm().getProvider()
            );
        } catch (RuntimeException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "database unavailable");
        }
    }
}
