package io.clusterinfra.rca.webconsole.persistence;

import io.clusterinfra.rca.webconsole.domain.RcaModels.UserAccount;
import io.clusterinfra.rca.webconsole.domain.RcaModels.UserRole;
import io.clusterinfra.rca.webconsole.domain.RcaModels.UserStatus;
import io.clusterinfra.rca.webconsole.security.Sha256Digest;
import io.clusterinfra.rca.webconsole.security.TokenGenerator;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class UserSessionRepository {
    private final JdbcTemplate jdbc;
    private final TokenGenerator tokenGenerator;
    private final Sha256Digest digests;

    public UserSessionRepository(
        JdbcTemplate jdbc,
        TokenGenerator tokenGenerator,
        Sha256Digest digests
    ) {
        this.jdbc = jdbc;
        this.tokenGenerator = tokenGenerator;
        this.digests = digests;
    }

    public Optional<UserAccount> findUserByToken(String token) {
        String tokenHash = digests.digest(token);
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                """
                    SELECT u.* FROM user_sessions s
                    JOIN user_accounts u ON u.user_id = s.user_id
                    WHERE s.token_hash = ? AND s.revoked_at IS NULL AND s.expires_at > ? AND u.status = ?
                    """,
                (resultSet, rowNumber) -> mapUserAccount(resultSet),
                tokenHash,
                timestamp(Instant.now()),
                UserStatus.active.name()
            ));
        } catch (EmptyResultDataAccessException exception) {
            return Optional.empty();
        }
    }

    public String create(String userId, Instant expiresAt) {
        String token = tokenGenerator.generate();
        jdbc.update(
            """
                INSERT INTO user_sessions
                    (session_id, user_id, token_hash, created_at, expires_at, revoked_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
            id("session"),
            userId,
            digests.digest(token),
            timestamp(Instant.now()),
            timestamp(expiresAt),
            null
        );
        return token;
    }

    public boolean revoke(String token) {
        return jdbc.update(
            "UPDATE user_sessions SET revoked_at = ? WHERE token_hash = ? AND revoked_at IS NULL",
            timestamp(Instant.now()),
            digests.digest(token)
        ) > 0;
    }

    public int deleteExpiredBefore(Instant cutoff) {
        return jdbc.update("DELETE FROM user_sessions WHERE expires_at < ?", timestamp(cutoff));
    }

    private UserAccount mapUserAccount(ResultSet resultSet) throws SQLException {
        return new UserAccount(
            resultSet.getString("user_id"),
            resultSet.getString("email"),
            resultSet.getString("full_name"),
            UserRole.valueOf(resultSet.getString("requested_role")),
            enumOrNull(UserRole.class, resultSet.getString("role")),
            UserStatus.valueOf(resultSet.getString("status")),
            resultSet.getString("reason"),
            resultSet.getString("approval_note"),
            resultSet.getString("approved_by"),
            instant(resultSet, "created_at"),
            instant(resultSet, "approved_at")
        );
    }

    private Instant instant(ResultSet resultSet, String column) throws SQLException {
        Timestamp value = resultSet.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static String id(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private static <E extends Enum<E>> E enumOrNull(Class<E> type, String value) {
        return value == null ? null : Enum.valueOf(type, value);
    }
}
