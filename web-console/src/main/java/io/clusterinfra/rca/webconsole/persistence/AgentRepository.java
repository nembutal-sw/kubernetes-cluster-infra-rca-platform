package io.clusterinfra.rca.webconsole.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.clusterinfra.rca.webconsole.domain.RcaModels.AgentStatus;
import io.clusterinfra.rca.webconsole.domain.RcaModels.NodeAgent;
import io.clusterinfra.rca.webconsole.domain.RcaModels.NodeAgentHeartbeatRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.NodeAgentRegisterRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.NodeAgentRegistrationResponse;
import io.clusterinfra.rca.webconsole.security.OpaqueTokenHasher;
import io.clusterinfra.rca.webconsole.security.PasswordHasher;
import io.clusterinfra.rca.webconsole.security.TokenGenerator;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Repository
public class AgentRepository {
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final TokenGenerator tokenGenerator;
    private final OpaqueTokenHasher opaqueTokens;
    private final PasswordHasher legacyPasswords;
    private final ClusterRepository clusters;

    public AgentRepository(
        JdbcTemplate jdbc,
        ObjectMapper objectMapper,
        TokenGenerator tokenGenerator,
        OpaqueTokenHasher opaqueTokens,
        PasswordHasher legacyPasswords,
        ClusterRepository clusters
    ) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.tokenGenerator = tokenGenerator;
        this.opaqueTokens = opaqueTokens;
        this.legacyPasswords = legacyPasswords;
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
        RegistrationIdentity enrollmentIdentity = registrationIdentity(trustedEnrollmentMetadata);
        requireCurrentEnrollmentProfile(request.clusterId(), enrollmentIdentity);
        existing.ifPresent(agent -> requireRegistrationContinuity(
            agent,
            registrationState(request.clusterId(), nodeName),
            enrollmentIdentity
        ));
        String nodeToken = tokenGenerator.generate();
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
                        enrollment_profile_version = ?, enrollment_service_account_uid = ?,
                        enrollment_daemonset_uid = ?, registered_at = ?, last_heartbeat_at = ?
                    WHERE agent_id = ?
                    """,
                opaqueTokens.hash(nodeToken),
                agent.agentVersion(),
                timestamp(now),
                agent.agentProtocolVersion(),
                agent.status().name(),
                json(agent.supportedCollectors()),
                json(agent.metadata()),
                json(agent.health()),
                enrollmentIdentity.profileVersion(),
                enrollmentIdentity.serviceAccountUid(),
                enrollmentIdentity.daemonSetUid(),
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
                         supported_collectors_json, metadata_json, health_json,
                         enrollment_profile_version, enrollment_service_account_uid,
                         enrollment_daemonset_uid, registered_at, last_heartbeat_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                agent.agentId(),
                agent.clusterId(),
                agent.nodeName(),
                opaqueTokens.hash(nodeToken),
                agent.agentVersion(),
                timestamp(now),
                null,
                agent.agentProtocolVersion(),
                agent.status().name(),
                json(agent.supportedCollectors()),
                json(agent.metadata()),
                json(agent.health()),
                enrollmentIdentity.profileVersion(),
                enrollmentIdentity.serviceAccountUid(),
                enrollmentIdentity.daemonSetUid(),
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

    private RegistrationIdentity registrationIdentity(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return RegistrationIdentity.bootstrap();
        }
        Object rawVersion = metadata.get("profile_version");
        Long profileVersion = rawVersion instanceof Number number
            ? number.longValue()
            : null;
        RegistrationIdentity identity = new RegistrationIdentity(
            stringValue(metadata.get("method")),
            profileVersion,
            stringValue(metadata.get("service_account_uid")),
            stringValue(metadata.get("daemonset_uid")),
            stringValue(metadata.get("pod_uid"))
        );
        if (identity.kubernetesTokenReview() && !identity.complete()) {
            throw new IllegalArgumentException("trusted Kubernetes enrollment identity is incomplete");
        }
        return identity;
    }

    private void requireCurrentEnrollmentProfile(
        String clusterId,
        RegistrationIdentity identity
    ) {
        if (!identity.kubernetesTokenReview()) {
            return;
        }
        Long currentVersion = optionalQuery(
            "SELECT profile_version FROM agent_enrollment_profiles WHERE cluster_id = ?",
            (resultSet, rowNumber) -> resultSet.getLong("profile_version"),
            clusterId
        ).orElse(null);
        if (!Objects.equals(currentVersion, identity.profileVersion())) {
            throw conflict("agent enrollment profile changed while the workload identity was verified");
        }
    }

    private void requireRegistrationContinuity(
        NodeAgent existing,
        RegistrationState stored,
        RegistrationIdentity incoming
    ) {
        RegistrationIdentity previous = registrationIdentity(enrollmentMetadata(existing.metadata()));
        if (!incoming.kubernetesTokenReview()) {
            if (previous.kubernetesTokenReview() && stored.revokedAt() == null) {
                throw conflict("an active Kubernetes workload identity cannot be replaced by bootstrap enrollment");
            }
            return;
        }
        if (stored.profileVersion() != null
            && incoming.profileVersion() != null
            && incoming.profileVersion() < stored.profileVersion()) {
            throw conflict("agent enrollment profile version is older than the registered identity");
        }
        if (stored.revokedAt() == null) {
            if (!previous.samePod(incoming)) {
                throw conflict(
                    "agent identity already exists; revoke its node token before binding a replacement Pod"
                );
            }
            return;
        }
        if (stored.profileVersion() != null
            && Objects.equals(stored.profileVersion(), incoming.profileVersion())
            && !stored.sameWorkload(incoming)) {
            throw conflict("revoked agent identity does not match the approved workload binding");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> enrollmentMetadata(Map<String, Object> metadata) {
        Object value = metadata == null ? null : metadata.get("_enrollment");
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private String stringValue(Object value) {
        return value == null ? null : value.toString().trim();
    }

    private RegistrationState registrationState(String clusterId, String nodeName) {
        return jdbc.queryForObject(
            """
                SELECT node_token_revoked_at, enrollment_profile_version,
                       enrollment_service_account_uid, enrollment_daemonset_uid
                FROM node_agents WHERE cluster_id = ? AND node_name = ?
                """,
            (resultSet, rowNumber) -> new RegistrationState(
                instant(resultSet, "node_token_revoked_at"),
                nullableLong(resultSet, "enrollment_profile_version"),
                resultSet.getString("enrollment_service_account_uid"),
                resultSet.getString("enrollment_daemonset_uid")
            ),
            clusterId,
            nodeName
        );
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
                    SELECT n.node_token_hash, n.node_token_revoked_at,
                           n.next_node_token_hash, n.next_node_token_expires_at,
                           n.enrollment_profile_version,
                           p.profile_version AS current_profile_version
                    FROM node_agents n
                    LEFT JOIN agent_enrollment_profiles p ON p.cluster_id = n.cluster_id
                    WHERE n.cluster_id = ? AND n.node_name = ?
                    """,
                (resultSet, rowNumber) -> new NodeTokenRow(
                    resultSet.getString("node_token_hash"),
                    instant(resultSet, "node_token_revoked_at"),
                    resultSet.getString("next_node_token_hash"),
                    instant(resultSet, "next_node_token_expires_at"),
                    nullableLong(resultSet, "enrollment_profile_version"),
                    nullableLong(resultSet, "current_profile_version")
                ),
                clusterId,
                normalizedNodeName(nodeName)
            );
            if (row == null || row.revokedAt() != null || !row.currentProfile()) {
                return false;
            }
            if (matchesCurrentToken(clusterId, nodeName, nodeToken, row.hash())) {
                return true;
            }
            if (row.nextHash() == null || row.nextExpiresAt() == null
                || !Instant.now().isBefore(row.nextExpiresAt())) {
                return false;
            }
            StoredTokenVerification nextVerification =
                verifyStoredToken(nodeToken, row.nextHash());
            if (!nextVerification.matched()) {
                return false;
            }
            String promotedHash = nextVerification.rehashRequired()
                ? opaqueTokens.hash(nodeToken)
                : row.nextHash();
            int promoted = jdbc.update(
                """
                    UPDATE node_agents
                    SET node_token_hash = ?, node_token_rotated_at = ?,
                        next_node_token_hash = NULL, next_node_token_expires_at = NULL
                    WHERE cluster_id = ? AND node_name = ? AND next_node_token_hash = ?
                    """,
                promotedHash,
                timestamp(Instant.now()),
                clusterId,
                normalizedNodeName(nodeName),
                row.nextHash()
            );
            return promoted == 1 || matchesLatestActiveToken(clusterId, nodeName, nodeToken);
        } catch (EmptyResultDataAccessException exception) {
            return false;
        }
    }

    public String rotateNodeToken(String clusterId, String nodeName) {
        String token = tokenGenerator.generate();
        int updated = jdbc.update(
            """
                UPDATE node_agents
                SET next_node_token_hash = ?, next_node_token_expires_at = ?
                WHERE cluster_id = ? AND node_name = ?
                """,
            opaqueTokens.hash(token),
            timestamp(Instant.now().plusSeconds(600)),
            clusterId,
            normalizedNodeName(nodeName)
        );
        if (updated != 1) {
            throw new IllegalArgumentException("agent not found: " + clusterId + "/" + nodeName);
        }
        return token;
    }

    private boolean matchesCurrentToken(
        String clusterId,
        String nodeName,
        String token,
        String storedHash
    ) {
        StoredTokenVerification verification = verifyStoredToken(token, storedHash);
        if (!verification.matched()) {
            return false;
        }
        if (verification.rehashRequired()) {
            int upgraded = jdbc.update(
                """
                    UPDATE node_agents
                    SET node_token_hash = ?
                    WHERE cluster_id = ? AND node_name = ? AND node_token_hash = ?
                    """,
                opaqueTokens.hash(token),
                clusterId,
                normalizedNodeName(nodeName),
                storedHash
            );
            return upgraded == 1 || matchesLatestActiveToken(clusterId, nodeName, token);
        }
        return true;
    }

    private boolean matchesLatestActiveToken(String clusterId, String nodeName, String token) {
        try {
            NodeTokenRow latest = jdbc.queryForObject(
                """
                    SELECT n.node_token_hash, n.node_token_revoked_at,
                           n.next_node_token_hash, n.next_node_token_expires_at,
                           n.enrollment_profile_version,
                           p.profile_version AS current_profile_version
                    FROM node_agents n
                    LEFT JOIN agent_enrollment_profiles p ON p.cluster_id = n.cluster_id
                    WHERE n.cluster_id = ? AND n.node_name = ?
                    """,
                (resultSet, rowNumber) -> new NodeTokenRow(
                    resultSet.getString("node_token_hash"),
                    instant(resultSet, "node_token_revoked_at"),
                    resultSet.getString("next_node_token_hash"),
                    instant(resultSet, "next_node_token_expires_at"),
                    nullableLong(resultSet, "enrollment_profile_version"),
                    nullableLong(resultSet, "current_profile_version")
                ),
                clusterId,
                normalizedNodeName(nodeName)
            );
            return latest != null
                && latest.revokedAt() == null
                && latest.currentProfile()
                && opaqueTokens.matches(token, latest.hash());
        } catch (EmptyResultDataAccessException exception) {
            return false;
        }
    }

    private StoredTokenVerification verifyStoredToken(
        String token,
        String storedHash
    ) {
        OpaqueTokenHasher.Verification opaqueVerification =
            opaqueTokens.verify(token, storedHash);
        if (opaqueVerification.matched()) {
            return new StoredTokenVerification(
                true,
                opaqueVerification.rehashRequired()
            );
        }
        boolean legacyVerified = legacyPasswords.supports(storedHash)
            && legacyPasswords.matches(token, storedHash);
        return new StoredTokenVerification(legacyVerified, legacyVerified);
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

    public int revokeNodeTokensForEnrollmentChange(String clusterId) {
        return jdbc.update(
            """
                UPDATE node_agents SET node_token_revoked_at = ?,
                    next_node_token_hash = NULL, next_node_token_expires_at = NULL
                WHERE cluster_id = ? AND node_token_revoked_at IS NULL
                """,
            timestamp(Instant.now()),
            clusterId
        );
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

    private Long nullableLong(ResultSet resultSet, String column) throws SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : value;
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
        Instant nextExpiresAt,
        Long enrollmentProfileVersion,
        Long currentProfileVersion
    ) {
        private boolean currentProfile() {
            return enrollmentProfileVersion == null
                || Objects.equals(enrollmentProfileVersion, currentProfileVersion);
        }
    }

    private record StoredTokenVerification(
        boolean matched,
        boolean rehashRequired
    ) {
    }

    private record RegistrationState(
        Instant revokedAt,
        Long profileVersion,
        String serviceAccountUid,
        String daemonSetUid
    ) {
        private boolean sameWorkload(RegistrationIdentity identity) {
            return identity != null
                && Objects.equals(profileVersion, identity.profileVersion())
                && Objects.equals(serviceAccountUid, identity.serviceAccountUid())
                && Objects.equals(daemonSetUid, identity.daemonSetUid());
        }
    }

    private record RegistrationIdentity(
        String method,
        Long profileVersion,
        String serviceAccountUid,
        String daemonSetUid,
        String podUid
    ) {
        private static RegistrationIdentity bootstrap() {
            return new RegistrationIdentity("bootstrap_token", null, null, null, null);
        }

        private boolean kubernetesTokenReview() {
            return "kubernetes_token_review".equals(method);
        }

        private boolean complete() {
            return profileVersion != null
                && profileVersion > 0
                && serviceAccountUid != null && !serviceAccountUid.isBlank()
                && daemonSetUid != null && !daemonSetUid.isBlank()
                && podUid != null && !podUid.isBlank();
        }

        private boolean sameWorkload(RegistrationIdentity other) {
            return other != null
                && Objects.equals(profileVersion, other.profileVersion)
                && Objects.equals(serviceAccountUid, other.serviceAccountUid)
                && Objects.equals(daemonSetUid, other.daemonSetUid);
        }

        private boolean samePod(RegistrationIdentity other) {
            return sameWorkload(other) && Objects.equals(podUid, other.podUid);
        }
    }

    private ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}
