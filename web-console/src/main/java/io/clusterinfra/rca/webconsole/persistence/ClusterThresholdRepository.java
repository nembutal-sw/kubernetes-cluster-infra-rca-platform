package io.clusterinfra.rca.webconsole.persistence;

import io.clusterinfra.rca.webconsole.domain.RcaModels.ClusterThresholdOverride;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ClusterThresholdRepository {
    private final JdbcTemplate jdbc;

    public ClusterThresholdRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<ClusterThresholdOverride> list(String clusterId) {
        return jdbc.query(
            """
                SELECT cluster_id, threshold_key, threshold_value, reason, updated_by,
                       created_at, updated_at
                FROM cluster_threshold_overrides
                WHERE cluster_id = ?
                ORDER BY threshold_key
                """,
            this::mapOverride,
            clusterId
        );
    }

    public Map<String, Double> values(String clusterId) {
        Map<String, Double> values = new LinkedHashMap<>();
        for (ClusterThresholdOverride override : list(clusterId)) {
            values.put(override.key(), override.value());
        }
        return values;
    }

    public Instant latestUpdatedAt(String clusterId) {
        return jdbc.query(
            "SELECT MAX(updated_at) FROM cluster_threshold_overrides WHERE cluster_id = ?",
            resultSet -> resultSet.next() ? instant(resultSet, 1) : null,
            clusterId
        );
    }

    @Transactional
    public void replace(String clusterId, Map<String, Double> values, String reason, String updatedBy) {
        jdbc.update("DELETE FROM cluster_threshold_overrides WHERE cluster_id = ?", clusterId);
        if (values == null || values.isEmpty()) {
            return;
        }
        Instant now = Instant.now();
        for (Map.Entry<String, Double> entry : values.entrySet()) {
            jdbc.update(
                """
                    INSERT INTO cluster_threshold_overrides
                        (cluster_id, threshold_key, threshold_value, reason, updated_by, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """,
                clusterId,
                entry.getKey(),
                entry.getValue(),
                blankToNull(reason),
                blankToNull(updatedBy),
                timestamp(now),
                timestamp(now)
            );
        }
    }

    public void deleteAll(String clusterId) {
        jdbc.update("DELETE FROM cluster_threshold_overrides WHERE cluster_id = ?", clusterId);
    }

    private ClusterThresholdOverride mapOverride(ResultSet resultSet, int rowNumber) throws SQLException {
        return new ClusterThresholdOverride(
            resultSet.getString("cluster_id"),
            resultSet.getString("threshold_key"),
            resultSet.getDouble("threshold_value"),
            resultSet.getString("reason"),
            resultSet.getString("updated_by"),
            instant(resultSet, "created_at"),
            instant(resultSet, "updated_at")
        );
    }

    private Instant instant(ResultSet resultSet, String column) throws SQLException {
        Timestamp value = resultSet.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private Instant instant(ResultSet resultSet, int column) throws SQLException {
        Timestamp value = resultSet.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
