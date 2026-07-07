package io.clusterinfra.rca.webconsole.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.clusterinfra.rca.webconsole.domain.RcaModels.UserRole;
import io.clusterinfra.rca.webconsole.domain.RcaModels.UserStatus;
import io.clusterinfra.rca.webconsole.security.TokenService;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class UserRepositoryTests {
    private JdbcTemplate jdbc;
    private TokenService tokens;
    private UserRepository users;
    private UserSessionRepository sessions;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
            "jdbc:h2:mem:user-repository-" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
            "sa",
            ""
        );
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        tokens = new TokenService();
        users = new UserRepository(jdbc, tokens);
        sessions = new UserSessionRepository(jdbc, tokens);
    }

    @Test
    void defaultAdminAuthenticationAndSessionLifecycleWork() {
        var admin = users.ensureDefaultAdmin("ADMIN", "admin");

        assertThat(admin.email()).isEqualTo("admin");
        assertThat(admin.role()).isEqualTo(UserRole.admin);
        assertThat(admin.status()).isEqualTo(UserStatus.active);
        assertThat(users.authenticate("admin", "admin")).contains(admin);

        String token = sessions.create(admin.userId(), Instant.now().plusSeconds(3600));
        assertThat(sessions.findUserByToken(token)).contains(admin);
        assertThat(sessions.revoke(token)).isTrue();
        assertThat(sessions.findUserByToken(token)).isEmpty();

        sessions.create(admin.userId(), Instant.now().minusSeconds(60));
        assertThat(sessions.deleteExpiredBefore(Instant.now())).isEqualTo(1);
    }

    @Test
    void passwordAndLoginIdCanBeChangedAfterCurrentPasswordVerification() {
        var admin = users.ensureDefaultAdmin("admin", "admin");

        assertThat(users.changePassword(admin.userId(), "wrong", "new-secret")).isFalse();
        assertThat(users.changePassword(admin.userId(), "admin", "new-secret")).isTrue();
        assertThat(users.authenticate("admin", "admin")).isEmpty();
        assertThat(users.authenticate("admin", "new-secret")).isPresent();

        var renamed = users.changeLoginId(admin.userId(), "new-secret", "ops-admin").orElseThrow();
        assertThat(renamed.email()).isEqualTo("ops-admin");
        assertThat(users.authenticate("admin", "new-secret")).isEmpty();
        assertThat(users.authenticate("ops-admin", "new-secret")).isPresent();

        seedUser("user-other", "duplicate", "other-secret");
        assertThatThrownBy(() -> users.changeLoginId(admin.userId(), "new-secret", "duplicate"))
            .isInstanceOf(DuplicateLoginIdException.class);
    }

    private void seedUser(String userId, String loginId, String password) {
        Instant now = Instant.now();
        jdbc.update(
            """
                INSERT INTO user_accounts
                    (user_id, email, full_name, password_hash, requested_role, role, status, reason,
                     approval_note, approved_by, created_at, approved_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
            userId,
            loginId,
            "Other User",
            tokens.hashPassword(password),
            UserRole.operator.name(),
            UserRole.operator.name(),
            UserStatus.active.name(),
            null,
            null,
            "system",
            Timestamp.from(now),
            Timestamp.from(now)
        );
    }
}
