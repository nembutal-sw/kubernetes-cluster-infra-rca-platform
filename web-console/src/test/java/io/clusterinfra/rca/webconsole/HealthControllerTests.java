package io.clusterinfra.rca.webconsole;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.clusterinfra.rca.webconsole.config.BootstrapReadiness;
import io.clusterinfra.rca.webconsole.config.RcaConsoleProperties;
import io.clusterinfra.rca.webconsole.controller.HealthController;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;

class HealthControllerTests {
    @Test
    void readinessRejectsTrafficUntilBootstrapCompletes() {
        BootstrapReadiness readiness = new BootstrapReadiness();
        HealthController controller = new HealthController(dataSource(), new RcaConsoleProperties(), readiness);

        assertThatThrownBy(controller::readiness)
            .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
            );
    }

    @Test
    void readinessReportsBootstrapCompletion() {
        BootstrapReadiness readiness = new BootstrapReadiness();
        readiness.markCompleted();
        HealthController controller = new HealthController(dataSource(), new RcaConsoleProperties(), readiness);

        assertThat(controller.readiness())
            .containsEntry("status", "ok")
            .containsEntry("database", "reachable")
            .containsEntry("bootstrap", "completed");
    }

    private DriverManagerDataSource dataSource() {
        return new DriverManagerDataSource(
            "jdbc:h2:mem:health-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1",
            "sa",
            ""
        );
    }
}
