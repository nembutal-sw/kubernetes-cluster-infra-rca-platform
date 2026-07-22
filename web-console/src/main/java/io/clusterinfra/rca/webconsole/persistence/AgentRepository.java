package io.clusterinfra.rca.webconsole.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.clusterinfra.rca.webconsole.domain.RcaModels.AgentStatus;
import io.clusterinfra.rca.webconsole.domain.RcaModels.NodeAgent;
import io.clusterinfra.rca.webconsole.domain.RcaModels.NodeAgentHeartbeatRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.NodeAgentRegisterRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.NodeAgentRegistrationResponse;
import io.clusterinfra.rca.webconsole.security.TokenService;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class AgentRepository {
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final TokenService tokens;
    private final ClusterRepository clusters;

    public AgentRepository(
        JdbcTemplate jdbc,
        ObjectMapper objectMapper,
        TokenService tokens,
        ClusterRepository clusters
    ) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.tokens = tokens;
        this.clusters = clusters;
    }

    @Transactional
    public NodeAgentRegistrationResponse register(NodeAgentRegisterRequest request) {
        return register(request, Map.of());
    }

    @Transactional
    public NodeAgentRegistrationResponse register(
        NodeAgentRegisterRequest request,
        Map<String, Object> trustedEnrollmentMetadata
    ) {
        String nodeName = normalizedNodeName(request.nodeName());
        Optional<NodeAgent> existing = find(request.clusterId(), nodeName);
        String nodeToken = tokens.generateToken();
        Instant now = Instant.now();
        NodeAgent agent = new NodeAgent(
            existing.map(NodeAgent::agentId).orElseGet(() -> id("agent")),
            request.clusterId(),
            nodeName,
            request.agentVersion().trim(),
            request.protocolVersionOrDefault(),
            AgentStatus.registered,
            request.collectorsOrEmpty(),
            registrationMetadata(request.metadataOrEmpty(), trustedEnrollmentMetadata),
            existing.map(NodeAgent::health).orElse(Map.of()),
            existing.map(NodeAgent::registeredAt).orElse(now),
            existing.map(NodeAgent::lastHeartbeatAt).orElse(null)
        );
        if (existing.isPresent()) {
            jdbc.update(
                """
                    UPDATE node_agents SET node_token_hash = ?, agent_version = ?,
                        node_token_rotated_at = ?, node_token_revoked_at = NULL,
                        next_node_token_hash = NULL, next_node_token_expires_at = NULL,
                        agent_protocol_version = ?, status = ?,
                        supported_collectors_json = ?, metadata_json = ?, health_json = ?,
                        registered_at = ?, last_heartbeat_at = ?
                    WHERE agent_id = ?
                    """,
                tokens.hashPassword(nodeToken),
                agent.agentVersion(),
                timestamp(now),
                agent.agentProtocolVersion(),
                agent.status().name(),
                json(agent.supportedCollectors()),
                json(agent.metadata()),
                json(agent.health()),
                timestamp(agent.registeredAt()),
                timestamp(agent.lastHeartbeatAt()),
                agent.agentId()
            );
        } else {
            jdbc.update(
                """
                    INSERT INTO node_agents
                        (agent_id, cluster_id, node_name, node_token_hash, agent_version,
                         node_token_rotated_at, node_token_revoked_at,
                         agent_protocol_version, status,
                         supported_collectors_json, metadata_json, health_json, registered_at, last_heartbeat_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                agent.agentId(),
                agent.clusterId(),
                agent.nodeName(),
                tokens.hashPassword(nodeToken),
                agent.agentVersion(),
                timestamp(now),
                null,
                agent.agentProtocolVersion(),
                agent.status().name(),
                json(agent.supportedCollectors()),
                json(agent.metadata()),
                json(agent.health()),
                timestamp(agent.registeredAt()),
                timestamp(agent.lastHeartbeatAt())
            );
        }
        clusters.markActive(request.clusterId());
        return new NodeAgentRegistrationResponse(
            agent.agentId(),
            agent.clusterId(),
            agent.nodeName(),
            agent.agentVersion(),
            agent.agentProtocolVersion(),
            agent.status(),
            agent.supportedCollectors(),
            agent.metadata(),
            agent.health(),
            agent.registeredAt(),
            agent.lastHeartbeatAt(),
            nodeToken
        );
    }

    private Map<String, Object> registrationMetadata(
        Map<String, Object> supplied,
        Map<String, Object> trustedEnrollmentMetadata
    ) {
        Map<String, Object> metadata = new LinkedHashMap<>(supplied == null ? Map.of() : supplied);
        metadata.remove("_enrollment");
        if (trustedEnrollmentMetadata != null && !trustedEnrollmentMetadata.isEmpty()) {
            metadata.put(
                "_enrollment",
                Collections.unmodifiableMap(new LinkedHashMap<>(trustedEnrollmentMetadata))
            );
        }
        return Collections.unmodifiableMap(metadata);
    }

    @Transactional
    public Optional<NodeAgent> heartbeat(NodeAgentHeartbeatRequest request) {
        String nodeName = normalizedNodeName(request.nodeName());
        Optional<NodeAgent> existing = find(request.clusterId(), nodeName);
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        NodeAgent current = existing.get();
        Instant now = Instant.now();
        jdbc.update(
            """
                UPDATE node_agents SET agent_version = ?, agent_protocol_version = ?, status = ?,
                    supported_collectors_json = ?,
                    health_json = ?, last_heartbeat_at = ?
                WHERE cluster_id = ? AND node_name = ?
                """,
            request.agentVersion() == null || request.agentVersion().isBlank()
                ? current.agentVersion()
                : request.agentVersion().trim(),
            request.protocolVersionOrDefault(),
            request.statusOrDefault().name(),
            json(request.supportedCollectors() == null ? current.supportedCollectors() : request.supportedCollectors()),
            json(request.healthOrEmpty()),
            timestamp(now),
            request.clusterId(),
            nodeName
        );
        clusters.markActive(request.clusterId());
        return find(request.clusterId(), nodeName);
    }

    public List<NodeAgent> list(String clusterId) {
        return jdbc.query(
            "SELECT * FROM node_agents WHERE cluster_id = ? ORDER BY node_name ASC",
            this::mapAgent,
            clusterId
        );
    }

    public List<NodeAgent> listAll() {
        return jdbc.query(
            "SELECT * FROM node_agents ORDER BY cluster_id, node_name",
            this::mapAgent
        );
    }

    public Optional<NodeAgent> find(String clusterId, String nodeName) {
        return optionalQuery(
            "SELECT * FROM node_agents WHERE cluster_id = ? AND node_name = ?",
            this::mapAgent,
            clusterId,
            normalizedNodeName(nodeName)
        );
    }

    @Transactional
    public boolean verifyNodeToken(String clusterId, String nodeName, String nodeToken) {
        if (nodeToken == null || nodeToken.isBlank()) {
            return false;
        }
        try {
            NodeTokenRow row = jdbc.queryForObject(
                """
                    SELECT node_token_hash, node_token_revoked_at,
                           next_node_token_hash, next_node_token_expires_at
                    FROM node_agents WHERE cluster_id = ? AND node_name = ?
                    """,
                (resultSet, rowNumber) -> new NodeTokenRow(
                    resultSet.getString("node_token_hash"),
                    instant(resultSet, "node_token_revoked_at"),
                    resultSet.getString("next_node_token_hash"),
                    instant(resultSet, "next_node_token_expires_at")
                ),
                clusterId,
                normalizedNodeName(nodeName)
            );
            if (row == null || row.revokedAt() != null) {
                return false;
            }
            if (row.hash() != null && tokens.verifyPassword(nodeToken, row.hash())) {
                return true;
            }
            if (row.nextHash() == null || row.nextExpiresAt() == null
                || !Instant.now().isBefore(row.nextExpiresAt())
                || !tokens.verifyPassword(nodeToken, row.nextHash())) {
                return false;
            }
            jdbc.update(
                """
                    UPDATE node_agents
                    SET node_token_hash = next_node_token_hash, node_token_rotated_at = ?,
                        next_node_token_hash = NULL, next_node_token_expires_at = NULL
                    WHERE cluster_id = ? AND node_name = ? AND next_node_token_hash = ?
                    """,
                timestamp(Instant.now()),
                clusterId,
                normalizedNodeName(nodeName),
                row.nextHash()
            );
            return true;
        } catch (EmptyResultDataAccessException exception) {
            return false;
        }
    }

    public String rotateNodeToken(String clusterId, String nodeName) {
        String token = tokens.generateToken();
        int updated = jdbc.update(
            """
                UPDATE node_agents
                SET next_node_token_hash = ?, next_node_token_expires_at = ?
                WHERE cluster_id = ? AND node_name = ?
                """,
            tokens.hashPassword(token),
            timestamp(Instant.now().plusSeconds(600)),
            clusterId,
            normalizedNodeName(nodeName)
        );
        if (updated != 1) {
            throw new IllegalArgumentException("agent not found: " + clusterId + "/" + nodeName);
        }
        return token;
    }

    public boolean revokeNodeToken(String clusterId, String nodeName) {
        return jdbc.update(
            """
                UPDATE node_agents SET node_token_revoked_at = ?,
                    next_node_token_hash = NULL, next_node_token_expires_at = NULL
                WHERE cluster_id = ? AND node_name = ?
                """,
            timestamp(Instant.now()),
            clusterId,
            normalizedNodeName(nodeName)
        ) == 1;
    }

    private NodeAgent mapAgent(ResultSet resultSet, int rowNumber) throws SQLException {
        return new NodeAgent(
            resultSet.getString("agent_id"),
            resultSet.getString("cluster_id"),
            resultSet.getString("node_name"),
            resultSet.getString("agent_version"),
            resultSet.getString("agent_protocol_version"),
            AgentStatus.valueOf(resultSet.getString("status")),
            read(resultSet.getString("supported_collectors_json"), STRING_LIST, List.of()),
            read(resultSet.getString("metadata_json"), OBJECT_MAP, Map.of()),
            read(resultSet.getString("health_json"), OBJECT_MAP, Map.of()),
            instant(resultSet, "registered_at"),
            instant(resultSet, "last_heartbeat_at")
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

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("value cannot be serialized as JSON", exception);
        }
    }

    private <T> T read(String value, TypeReference<T> type, T fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return objectMapper.readValue(value, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("stored JSON is invalid", exception);
        }
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

    private static String normalizedNodeName(String nodeName) {
        return nodeName == null ? null : nodeName.trim();
    }

    private record NodeTokenRow(
        String hash,
        Instant revokedAt,
        String nextHash,
        Instant nextExpiresAt
    ) {
    }
}
