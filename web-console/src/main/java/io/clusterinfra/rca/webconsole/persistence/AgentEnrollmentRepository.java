package io.clusterinfra.rca.webconsole.persistence;

import io.clusterinfra.rca.webconsole.domain.RcaModels.AgentEnrollmentMode;
import io.clusterinfra.rca.webconsole.domain.RcaModels.AgentEnrollmentProfile;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AgentEnrollmentRepository {
    private final JdbcTemplate jdbc;

    public AgentEnrollmentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<AgentEnrollmentConfiguration> findConfiguration(String clusterId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                "SELECT * FROM agent_enrollment_profiles WHERE cluster_id = ?",
                this::mapConfiguration,
                clusterId
            ));
        } catch (EmptyResultDataAccessException exception) {
            return Optional.empty();
        }
    }

    public AgentEnrollmentProfile view(String clusterId) {
        return findConfiguration(clusterId)
            .map(AgentEnrollmentConfiguration::view)
            .orElseGet(() -> AgentEnrollmentProfile.bootstrap(clusterId, false));
    }

    public AgentEnrollmentConfiguration save(AgentEnrollmentConfiguration configuration) {
        int updated = update(configuration);
        if (updated == 0) {
            try {
                jdbc.update(
                    """
                        INSERT INTO agent_enrollment_profiles
                            (cluster_id, mode, api_server_url, ca_bundle_pem, ca_sha256, audience,
                             service_account_namespace, service_account_name,
                             bootstrap_fallback_allowed, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                    configuration.clusterId(),
                    configuration.mode().name(),
                    configuration.apiServerUrl(),
                    configuration.caBundlePem(),
                    configuration.caSha256(),
                    configuration.audience(),
                    configuration.namespace(),
                    configuration.serviceAccount(),
                    configuration.bootstrapFallbackAllowed(),
                    timestamp(configuration.createdAt()),
                    timestamp(configuration.updatedAt())
                );
            } catch (DuplicateKeyException exception) {
                update(configuration);
            }
        }
        return findConfiguration(configuration.clusterId()).orElseThrow();
    }

    public void delete(String clusterId) {
        jdbc.update("DELETE FROM agent_enrollment_profiles WHERE cluster_id = ?", clusterId);
    }

    private int update(AgentEnrollmentConfiguration configuration) {
        return jdbc.update(
            """
                UPDATE agent_enrollment_profiles
                SET mode = ?, api_server_url = ?, ca_bundle_pem = ?, ca_sha256 = ?, audience = ?,
                    service_account_namespace = ?, service_account_name = ?,
                    bootstrap_fallback_allowed = ?, updated_at = ?
                WHERE cluster_id = ?
                """,
            configuration.mode().name(),
            configuration.apiServerUrl(),
            configuration.caBundlePem(),
            configuration.caSha256(),
            configuration.audience(),
            configuration.namespace(),
            configuration.serviceAccount(),
            configuration.bootstrapFallbackAllowed(),
            timestamp(configuration.updatedAt()),
            configuration.clusterId()
        );
    }

    private AgentEnrollmentConfiguration mapConfiguration(ResultSet resultSet, int rowNumber) throws SQLException {
        return new AgentEnrollmentConfiguration(
            resultSet.getString("cluster_id"),
            AgentEnrollmentMode.valueOf(resultSet.getString("mode")),
            resultSet.getString("api_server_url"),
            resultSet.getString("ca_bundle_pem"),
            resultSet.getString("ca_sha256"),
            resultSet.getString("audience"),
            resultSet.getString("service_account_namespace"),
            resultSet.getString("service_account_name"),
            resultSet.getBoolean("bootstrap_fallback_allowed"),
            instant(resultSet, "created_at"),
            instant(resultSet, "updated_at")
        );
    }

    private Instant instant(ResultSet resultSet, String column) throws SQLException {
        Timestamp value = resultSet.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    public record AgentEnrollmentConfiguration(
        String clusterId,
        AgentEnrollmentMode mode,
        String apiServerUrl,
        String caBundlePem,
        String caSha256,
        String audience,
        String namespace,
        String serviceAccount,
        boolean bootstrapFallbackAllowed,
        Instant createdAt,
        Instant updatedAt
    ) {
        public AgentEnrollmentProfile view() {
            return new AgentEnrollmentProfile(
                clusterId,
                mode,
                true,
                apiServerUrl,
                caSha256,
                audience,
                namespace,
                serviceAccount,
                bootstrapFallbackAllowed,
                false,
                updatedAt
            );
        }
    }
}
