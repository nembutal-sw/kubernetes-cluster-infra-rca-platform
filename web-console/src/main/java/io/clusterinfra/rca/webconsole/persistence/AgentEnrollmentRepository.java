package io.clusterinfra.rca.webconsole.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.clusterinfra.rca.webconsole.domain.RcaModels.AgentEnrollmentMode;
import io.clusterinfra.rca.webconsole.domain.RcaModels.AgentEnrollmentProfile;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AgentEnrollmentRepository {
    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    @Autowired
    public AgentEnrollmentRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public AgentEnrollmentRepository(JdbcTemplate jdbc) {
        this(jdbc, new ObjectMapper());
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

    public List<AgentEnrollmentConfiguration> findAllConfigurations() {
        return jdbc.query(
            "SELECT * FROM agent_enrollment_profiles ORDER BY cluster_id",
            this::mapConfiguration
        );
    }

    public void lockCluster(String clusterId) {
        jdbc.queryForObject(
            "SELECT cluster_id FROM clusters WHERE cluster_id = ? FOR UPDATE",
            String.class,
            clusterId
        );
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
                             profile_version, reviewer_token_path, expected_service_account_uid,
                             expected_daemonset_name, expected_daemonset_uid,
                             required_pod_labels_json, allowed_image_digest,
                             legacy_token_grace_until,
                             bootstrap_fallback_allowed, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                    configuration.clusterId(),
                    configuration.mode().name(),
                    configuration.apiServerUrl(),
                    configuration.caBundlePem(),
                    configuration.caSha256(),
                    configuration.audience(),
                    configuration.namespace(),
                    configuration.serviceAccount(),
                    configuration.profileVersion(),
                    configuration.reviewerTokenPath(),
                    configuration.expectedServiceAccountUid(),
                    configuration.expectedDaemonSetName(),
                    configuration.expectedDaemonSetUid(),
                    json(configuration.requiredPodLabels()),
                    configuration.allowedImageDigest(),
                    timestamp(configuration.legacyUnboundTokenGraceUntil()),
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
                    profile_version = ?, reviewer_token_path = ?,
                    expected_service_account_uid = ?, expected_daemonset_name = ?,
                    expected_daemonset_uid = ?, required_pod_labels_json = ?,
                    allowed_image_digest = ?, legacy_token_grace_until = ?,
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
            configuration.profileVersion(),
            configuration.reviewerTokenPath(),
            configuration.expectedServiceAccountUid(),
            configuration.expectedDaemonSetName(),
            configuration.expectedDaemonSetUid(),
            json(configuration.requiredPodLabels()),
            configuration.allowedImageDigest(),
            timestamp(configuration.legacyUnboundTokenGraceUntil()),
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
            resultSet.getLong("profile_version"),
            resultSet.getString("reviewer_token_path"),
            resultSet.getString("expected_service_account_uid"),
            resultSet.getString("expected_daemonset_name"),
            resultSet.getString("expected_daemonset_uid"),
            labels(resultSet.getString("required_pod_labels_json")),
            resultSet.getString("allowed_image_digest"),
            instant(resultSet, "legacy_token_grace_until"),
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

    private String json(Map<String, String> value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("agent enrollment labels could not be serialized", exception);
        }
    }

    private Map<String, String> labels(String value) {
        try {
            if (value == null || value.isBlank()) {
                return Map.of();
            }
            return Map.copyOf(objectMapper.readValue(value, STRING_MAP));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("stored agent enrollment labels are invalid", exception);
        }
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
        long profileVersion,
        String reviewerTokenPath,
        String expectedServiceAccountUid,
        String expectedDaemonSetName,
        String expectedDaemonSetUid,
        Map<String, String> requiredPodLabels,
        String allowedImageDigest,
        Instant legacyUnboundTokenGraceUntil,
        boolean bootstrapFallbackAllowed,
        Instant createdAt,
        Instant updatedAt
    ) {
        public AgentEnrollmentConfiguration(
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
            this(
                clusterId, mode, apiServerUrl, caBundlePem, caSha256, audience, namespace,
                serviceAccount, 1, null, null, null, null, Map.of(), null,
                null, bootstrapFallbackAllowed, createdAt, updatedAt
            );
        }

        public AgentEnrollmentConfiguration(
            String clusterId,
            AgentEnrollmentMode mode,
            String apiServerUrl,
            String caBundlePem,
            String caSha256,
            String audience,
            String namespace,
            String serviceAccount,
            long profileVersion,
            String reviewerTokenPath,
            String expectedServiceAccountUid,
            String expectedDaemonSetName,
            String expectedDaemonSetUid,
            Map<String, String> requiredPodLabels,
            String allowedImageDigest,
            boolean bootstrapFallbackAllowed,
            Instant createdAt,
            Instant updatedAt
        ) {
            this(
                clusterId, mode, apiServerUrl, caBundlePem, caSha256, audience, namespace,
                serviceAccount, profileVersion, reviewerTokenPath, expectedServiceAccountUid,
                expectedDaemonSetName, expectedDaemonSetUid, requiredPodLabels,
                allowedImageDigest, null, bootstrapFallbackAllowed, createdAt, updatedAt
            );
        }

        public AgentEnrollmentConfiguration {
            requiredPodLabels = requiredPodLabels == null ? Map.of() : Map.copyOf(requiredPodLabels);
        }

        public boolean workloadIdentityReady() {
            return reviewerTokenPath != null && !reviewerTokenPath.isBlank()
                && expectedServiceAccountUid != null && !expectedServiceAccountUid.isBlank()
                && expectedDaemonSetName != null && !expectedDaemonSetName.isBlank()
                && expectedDaemonSetUid != null && !expectedDaemonSetUid.isBlank()
                && !requiredPodLabels.isEmpty()
                && allowedImageDigest != null && !allowedImageDigest.isBlank();
        }

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
                profileVersion,
                reviewerTokenPath,
                expectedServiceAccountUid,
                expectedDaemonSetName,
                expectedDaemonSetUid,
                requiredPodLabels,
                allowedImageDigest,
                workloadIdentityReady(),
                bootstrapFallbackAllowed,
                false,
                legacyUnboundTokenGraceUntil,
                List.of(),
                updatedAt
            );
        }
    }
}
