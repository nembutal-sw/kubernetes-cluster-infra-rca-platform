package io.clusterinfra.rca.webconsole.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class AuditRepositoryTests {
    private AuditRepository audits;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
            "jdbc:h2:mem:audit-repository-" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
            "sa",
            ""
        );
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        audits = new AuditRepository(new JdbcTemplate(dataSource), objectMapper());
    }

    @Test
    void saveListAndSearchAuditEvents() {
        var login = audits.save(
            "user",
            "admin",
            "auth.login",
            "session",
            null,
            "success",
            Map.of("client_ip", "10.0.0.10", "user_agent", "browser")
        );
        audits.save(
            "agent",
            "worker-a",
            "evidence.submit",
            "cluster",
            "cluster-1",
            "accepted",
            Map.of("node", "worker-a")
        );

        assertThat(audits.list(10)).hasSize(2);
        assertThat(audits.search(new AuditSearchCriteria(
            "user",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            10
        ))).extracting(event -> event.auditEventId()).containsExactly(login.auditEventId());
        assertThat(audits.search(new AuditSearchCriteria(
            null,
            null,
            null,
            null,
            null,
            null,
            "10.0.0.10",
            null,
            null,
            null,
            10
        ))).extracting(event -> event.auditEventId()).containsExactly(login.auditEventId());
        assertThat(audits.search(new AuditSearchCriteria(
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            "evidence",
            null,
            null,
            10
        ))).extracting(event -> event.eventType()).containsExactly("evidence.submit");
    }

    @Test
    void deleteBeforeRemovesOldAuditEvents() {
        audits.save(
            "user",
            "admin",
            "auth.login",
            "session",
            null,
            "success",
            Map.of("client_ip", "10.0.0.10")
        );

        assertThat(audits.deleteBefore(Instant.now().plusSeconds(1))).isEqualTo(1);
        assertThat(audits.list(10)).isEmpty();
    }

    private ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.findAndRegisterModules();
        mapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        return mapper;
    }
}
