package io.clusterinfra.rca.webconsole.persistence;

import io.clusterinfra.rca.webconsole.domain.RcaModels.Cluster;
import io.clusterinfra.rca.webconsole.domain.RcaModels.ClusterCreateRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.ClusterStatus;
import io.clusterinfra.rca.webconsole.security.OpaqueTokenHasher;
import io.clusterinfra.rca.webconsole.security.PasswordHasher;
import io.clusterinfra.rca.webconsole.security.TokenGenerator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ClusterRepository {
    private static final String STORED_BOOTSTRAP_TOKEN_SENTINEL = "";

    private final JdbcTemplate jdbc;
    private final TokenGenerator tokenGenerator;
    private final OpaqueTokenHasher opaqueTokens;
    private final PasswordHasher legacyPasswords;

    public ClusterRepository(
        JdbcTemplate jdbc,
        TokenGenerator tokenGenerator,
        OpaqueTokenHasher opaqueTokens,
        PasswordHasher legacyPasswords
    ) {
        this.jdbc = jdbc;
        this.tokenGenerator = tokenGenerator;
        this.opaqueTokens = opaqueTokens;
        this.legacyPasswords = legacyPasswords;
    }

    public Cluster create(ClusterCreateRequest request) {
        Instant now = Instant.now();
        String bootstrapToken = tokenGenerator.generate();
        Cluster cluster = new Cluster(
            id("cluster"),
            request.name().trim(),
            request.normalizedEnvironment(),
            blankToNull(request.description()),
            ClusterStatus.agent_pending,
            bootstrapToken,
            now,
            null
        );
        jdbc.update(
            """
                INSERT INTO clusters
                    (cluster_id, name, environment, description, status, bootstrap_token,
                     bootstrap_token_hash, bootstrap_token_rotated_at, created_at, last_seen_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
            cluster.clusterId(),
            cluster.name(),
            cluster.environment(),
            cluster.description(),
            cluster.status().name(),
            STORED_BOOTSTRAP_TOKEN_SENTINEL,
            opaqueTokens.hash(bootstrapToken),
            timestamp(now),
            timestamp(cluster.createdAt()),
            null
        );
        return cluster;
    }

    public List<Cluster> list() {
        return jdbc.query("SELECT * FROM clusters ORDER BY created_at DESC", this::mapCluster);
    }

    public Optional<Cluster> find(String clusterId) {
        return optionalQuery("SELECT * FROM clusters WHERE cluster_id = ?", this::mapCluster, clusterId);
    }

    public void markActive(String clusterId) {
        jdbc.update(
            "UPDATE clusters SET status = ?, last_seen_at = ? WHERE cluster_id = ?",
            ClusterStatus.active.name(),
            timestamp(Instant.now()),
            clusterId
        );
    }

    @Transactional
    public boolean delete(String clusterId) {
        if (find(clusterId).isEmpty()) {
            return false;
        }
        jdbc.update("DELETE FROM rca_analysis_tasks WHERE cluster_id = ?", clusterId);
        jdbc.update("DELETE FROM action_executions WHERE cluster_id = ?", clusterId);
        jdbc.update(
            """
                DELETE FROM action_requests
                WHERE report_id IN (SELECT report_id FROM rca_reports WHERE cluster_id = ?)
                """,
            clusterId
        );
        jdbc.update(
            """
                DELETE FROM notification_outbox
                WHERE report_id IN (SELECT report_id FROM rca_reports WHERE cluster_id = ?)
                """,
            clusterId
        );
        jdbc.update("DELETE FROM rca_jobs WHERE cluster_id = ?", clusterId);
        jdbc.update("DELETE FROM evidence_requests WHERE cluster_id = ?", clusterId);
        jdbc.update("DELETE FROM rca_reports WHERE cluster_id = ?", clusterId);
        jdbc.update(
            "UPDATE incidents SET recurrence_of_incident_id = NULL WHERE cluster_id = ?",
            clusterId
        );
        jdbc.update("DELETE FROM incidents WHERE cluster_id = ?", clusterId);
        jdbc.update("DELETE FROM realtime_events WHERE cluster_id = ?", clusterId);
        jdbc.update("DELETE FROM topology_observations WHERE cluster_id = ?", clusterId);
        jdbc.update("DELETE FROM manifest_download_tokens WHERE cluster_id = ?", clusterId);
        jdbc.update("DELETE FROM cluster_threshold_overrides WHERE cluster_id = ?", clusterId);
        jdbc.update("DELETE FROM agent_enrollment_profiles WHERE cluster_id = ?", clusterId);
        jdbc.update("DELETE FROM evidence_bundles WHERE cluster_id = ?", clusterId);
        jdbc.update("DELETE FROM node_agents WHERE cluster_id = ?", clusterId);
        return jdbc.update("DELETE FROM clusters WHERE cluster_id = ?", clusterId) == 1;
    }

    public Cluster rotateBootstrapToken(String clusterId) {
        String token = tokenGenerator.generate();
        Instant now = Instant.now();
        int updated = jdbc.update(
            """
                UPDATE clusters
                SET bootstrap_token = ?, bootstrap_token_hash = ?, bootstrap_token_rotated_at = ?,
                    bootstrap_token_revoked_at = NULL
                WHERE cluster_id = ?
                """,
            STORED_BOOTSTRAP_TOKEN_SENTINEL,
            opaqueTokens.hash(token),
            timestamp(now),
            clusterId
        );
        if (updated != 1) {
            throw new IllegalArgumentException("cluster not found: " + clusterId);
        }
        Cluster cluster = find(clusterId).orElseThrow();
        return new Cluster(
            cluster.clusterId(),
            cluster.name(),
            cluster.environment(),
            cluster.description(),
            cluster.status(),
            token,
            cluster.createdAt(),
            cluster.lastSeenAt()
        );
    }

    public boolean revokeBootstrapToken(String clusterId) {
        return jdbc.update(
            "UPDATE clusters SET bootstrap_token_revoked_at = ? WHERE cluster_id = ?",
            timestamp(Instant.now()),
            clusterId
        ) == 1;
    }

    public boolean bootstrapTokenRequiresRotation(String clusterId, Duration maximumAge) {
        try {
            BootstrapTokenState state = jdbc.queryForObject(
                """
                    SELECT bootstrap_token_revoked_at, bootstrap_token_rotated_at, created_at
                    FROM clusters
                    WHERE cluster_id = ?
                    """,
                (resultSet, rowNumber) -> new BootstrapTokenState(
                    instant(resultSet, "bootstrap_token_revoked_at"),
                    instant(resultSet, "bootstrap_token_rotated_at"),
                    instant(resultSet, "created_at")
                ),
                clusterId
            );
            if (state == null || state.revokedAt() != null) {
                return true;
            }
            Instant issuedAt = state.rotatedAt() == null ? state.createdAt() : state.rotatedAt();
            return maximumAge != null
                && (maximumAge.isZero() || maximumAge.isNegative()
                    || issuedAt == null || !Instant.now().isBefore(issuedAt.plus(maximumAge)));
        } catch (EmptyResultDataAccessException exception) {
            return true;
        }
    }

    @Transactional
    public boolean verifyBootstrapToken(String clusterId, String token) {
        return verifyBootstrapToken(clusterId, token, null);
    }

    @Transactional
    public boolean verifyBootstrapToken(String clusterId, String token, Duration maximumAge) {
        if (token == null || token.isBlank()) {
            return false;
        }
        try {
            ClusterTokenRow row = jdbc.queryForObject(
                """
                    SELECT bootstrap_token, bootstrap_token_hash, bootstrap_token_revoked_at,
                           bootstrap_token_rotated_at, created_at
                    FROM clusters
                    WHERE cluster_id = ?
                    """,
                (resultSet, rowNumber) -> new ClusterTokenRow(
                    resultSet.getString("bootstrap_token"),
                    resultSet.getString("bootstrap_token_hash"),
                    instant(resultSet, "bootstrap_token_revoked_at"),
                    instant(resultSet, "bootstrap_token_rotated_at"),
                    instant(resultSet, "created_at")
                ),
                clusterId
            );
            if (row == null || row.revokedAt() != null) {
                return false;
            }
            Instant issuedAt = row.rotatedAt() == null ? row.createdAt() : row.rotatedAt();
            if (maximumAge != null
                && (maximumAge.isZero() || maximumAge.isNegative()
                    || issuedAt == null || !Instant.now().isBefore(issuedAt.plus(maximumAge)))) {
                return false;
            }
            boolean verified = false;
            if (row.hash() != null && !row.hash().isBlank()) {
                OpaqueTokenHasher.Verification opaqueVerification =
                    opaqueTokens.verify(token, row.hash());
                boolean legacyVerified = !opaqueVerification.matched()
                    && legacyPasswords.supports(row.hash())
                    && legacyPasswords.matches(token, row.hash());
                verified = opaqueVerification.matched() || legacyVerified;
                if (verified
                    && (legacyVerified || opaqueVerification.rehashRequired())) {
                    int upgraded = jdbc.update(
                        """
                            UPDATE clusters SET bootstrap_token_hash = ?
                            WHERE cluster_id = ? AND bootstrap_token_hash = ?
                            """,
                        opaqueTokens.hash(token),
                        clusterId,
                        row.hash()
                    );
                    verified = upgraded == 1
                        || matchesLatestBootstrapToken(clusterId, token, maximumAge);
                }
            } else if (row.legacyPlaintextToken() != null && !row.legacyPlaintextToken().isBlank()) {
                verified = constantTimeEquals(row.legacyPlaintextToken(), token);
                if (verified) {
                    int upgraded = jdbc.update(
                        """
                            UPDATE clusters
                            SET bootstrap_token = ?, bootstrap_token_hash = ?, bootstrap_token_rotated_at = ?
                            WHERE cluster_id = ? AND bootstrap_token_hash IS NULL
                            """,
                        STORED_BOOTSTRAP_TOKEN_SENTINEL,
                        opaqueTokens.hash(token),
                        timestamp(Instant.now()),
                        clusterId
                    );
                    verified = upgraded == 1
                        || matchesLatestBootstrapToken(clusterId, token, maximumAge);
                }
            }
            if (verified) {
                jdbc.update(
                    "UPDATE clusters SET bootstrap_token_last_used_at = ? WHERE cluster_id = ?",
                    timestamp(Instant.now()),
                    clusterId
                );
            }
            return verified;
        } catch (EmptyResultDataAccessException exception) {
            return false;
        }
    }

    private boolean matchesLatestBootstrapToken(String clusterId, String token, Duration maximumAge) {
        try {
            ClusterTokenRow latest = jdbc.queryForObject(
                """
                    SELECT bootstrap_token, bootstrap_token_hash, bootstrap_token_revoked_at,
                           bootstrap_token_rotated_at, created_at
                    FROM clusters
                    WHERE cluster_id = ?
                    """,
                (resultSet, rowNumber) -> new ClusterTokenRow(
                    resultSet.getString("bootstrap_token"),
                    resultSet.getString("bootstrap_token_hash"),
                    instant(resultSet, "bootstrap_token_revoked_at"),
                    instant(resultSet, "bootstrap_token_rotated_at"),
                    instant(resultSet, "created_at")
                ),
                clusterId
            );
            if (latest == null || latest.revokedAt() != null) {
                return false;
            }
            Instant issuedAt = latest.rotatedAt() == null ? latest.createdAt() : latest.rotatedAt();
            if (maximumAge != null
                && (maximumAge.isZero() || maximumAge.isNegative()
                    || issuedAt == null || !Instant.now().isBefore(issuedAt.plus(maximumAge)))) {
                return false;
            }
            return opaqueTokens.matches(token, latest.hash());
        } catch (EmptyResultDataAccessException exception) {
            return false;
        }
    }

    private Cluster mapCluster(ResultSet resultSet, int rowNumber) throws SQLException {
        return new Cluster(
            resultSet.getString("cluster_id"),
            resultSet.getString("name"),
            resultSet.getString("environment"),
            resultSet.getString("description"),
            ClusterStatus.valueOf(resultSet.getString("status")),
            null,
            instant(resultSet, "created_at"),
            instant(resultSet, "last_seen_at")
        );
    }

    private <T> Optional<T> optionalQuery(
        String sql,
        org.springframework.jdbc.core.RowMapper<T> rowMapper,
        Object... parameters
    ) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(sql, rowMapper, parameters));
        } catch (EmptyResultDataAccessException exception) {
            return Optional.empty();
        }
    }

    private Instant instant(ResultSet resultSet, String column) throws SQLException {
        Timestamp value = resultSet.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return MessageDigest.isEqual(
            expected.getBytes(StandardCharsets.UTF_8),
            actual.getBytes(StandardCharsets.UTF_8)
        );
    }

    private static String id(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record ClusterTokenRow(
        String legacyPlaintextToken,
        String hash,
        Instant revokedAt,
        Instant rotatedAt,
        Instant createdAt
    ) {
    }

    private record BootstrapTokenState(
        Instant revokedAt,
        Instant rotatedAt,
        Instant createdAt
    ) {
    }
}
