package io.clusterinfra.rca.webconsole.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.clusterinfra.rca.webconsole.domain.RcaModels.TopologyEntity;
import io.clusterinfra.rca.webconsole.domain.RcaModels.TopologyObservation;
import io.clusterinfra.rca.webconsole.domain.RcaModels.TopologyRelation;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class TopologyRepository {
    private static final TypeReference<List<TopologyEntity>> ENTITY_LIST = new TypeReference<>() {
    };
    private static final TypeReference<List<TopologyRelation>> RELATION_LIST = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public TopologyRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public TopologyObservation save(TopologyObservation observation) {
        try {
            jdbc.update(
                """
                    INSERT INTO topology_observations
                        (observation_id, cluster_id, source_evidence_id, source_node_name,
                         observed_at, entities_json, relations_json,
                         node_inventory_collected, pod_inventory_collected,
                         inventory_collected, inventory_complete)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                observation.observationId(),
                observation.clusterId(),
                observation.sourceEvidenceId(),
                observation.sourceNodeName(),
                Timestamp.from(observation.observedAt()),
                json(observation.entities()),
                json(observation.relations()),
                observation.nodeInventoryCollected() ? 1 : 0,
                observation.podInventoryCollected() ? 1 : 0,
                observation.inventoryCollected() ? 1 : 0,
                observation.inventoryComplete() ? 1 : 0
            );
            return observation;
        } catch (DuplicateKeyException exception) {
            return findByEvidence(observation.sourceEvidenceId()).orElse(observation);
        }
    }

    public List<TopologyObservation> listRecent(
        String clusterId,
        Instant from,
        int requestedLimit
    ) {
        int limit = Math.max(1, Math.min(requestedLimit, 2000));
        return jdbc.query(
            """
                SELECT * FROM topology_observations
                WHERE cluster_id = ? AND observed_at >= ?
                ORDER BY observed_at DESC
                LIMIT ?
                """,
            this::map,
            clusterId,
            Timestamp.from(from),
            limit
        );
    }

    public List<TopologyObservation> listRange(
        String clusterId,
        Instant from,
        Instant to,
        int requestedLimit
    ) {
        int limit = Math.max(1, Math.min(requestedLimit, 2000));
        return jdbc.query(
            """
                SELECT * FROM topology_observations
                WHERE cluster_id = ? AND observed_at >= ? AND observed_at <= ?
                ORDER BY observed_at DESC
                LIMIT ?
                """,
            this::map,
            clusterId,
            Timestamp.from(from),
            Timestamp.from(to),
            limit
        );
    }

    public java.util.Optional<TopologyObservation> findByEvidence(String evidenceId) {
        List<TopologyObservation> results = jdbc.query(
            "SELECT * FROM topology_observations WHERE source_evidence_id = ?",
            this::map,
            evidenceId
        );
        return results.stream().findFirst();
    }

    private TopologyObservation map(ResultSet resultSet, int rowNumber) throws SQLException {
        return new TopologyObservation(
            resultSet.getString("observation_id"),
            resultSet.getString("cluster_id"),
            resultSet.getString("source_evidence_id"),
            resultSet.getString("source_node_name"),
            resultSet.getTimestamp("observed_at").toInstant(),
            read(resultSet.getString("entities_json"), ENTITY_LIST),
            read(resultSet.getString("relations_json"), RELATION_LIST),
            resultSet.getInt("node_inventory_collected") != 0,
            resultSet.getInt("pod_inventory_collected") != 0,
            resultSet.getInt("inventory_collected") != 0,
            resultSet.getInt("inventory_complete") != 0
        );
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("topology value cannot be serialized", exception);
        }
    }

    private <T> T read(String value, TypeReference<T> type) {
        try {
            return objectMapper.readValue(value, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("stored topology JSON is invalid", exception);
        }
    }
}
