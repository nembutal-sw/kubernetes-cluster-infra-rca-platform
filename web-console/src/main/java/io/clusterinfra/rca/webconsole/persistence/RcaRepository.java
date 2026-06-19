package io.clusterinfra.rca.webconsole.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.clusterinfra.rca.webconsole.domain.RcaModels;
import io.clusterinfra.rca.webconsole.domain.RcaModels.AgentEvidenceSubmitRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.AgentStatus;
import io.clusterinfra.rca.webconsole.domain.RcaModels.Cluster;
import io.clusterinfra.rca.webconsole.domain.RcaModels.ClusterCreateRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.ClusterStatus;
import io.clusterinfra.rca.webconsole.domain.RcaModels.EvidenceBundle;
import io.clusterinfra.rca.webconsole.domain.RcaModels.EvidenceRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.EvidenceRequestCreateRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.EvidenceRequestStatus;
import io.clusterinfra.rca.webconsole.domain.RcaModels.NodeAgent;
import io.clusterinfra.rca.webconsole.domain.RcaModels.NodeAgentHeartbeatRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.NodeAgentRegisterRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.NodeAgentRegistrationResponse;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RcaJob;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RcaJobStatus;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RcaReport;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RecommendedAction;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RootCauseCandidate;
import io.clusterinfra.rca.webconsole.domain.RcaModels.UserAccount;
import io.clusterinfra.rca.webconsole.domain.RcaModels.UserRole;
import io.clusterinfra.rca.webconsole.domain.RcaModels.UserStatus;
import io.clusterinfra.rca.webconsole.security.TokenService;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class RcaRepository {
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() {
    };
    private static final TypeReference<List<Map<String, Object>>> MAP_LIST = new TypeReference<>() {
    };
    private static final TypeReference<List<RootCauseCandidate>> CANDIDATE_LIST = new TypeReference<>() {
    };
    private static final TypeReference<List<RecommendedAction>> ACTION_LIST = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final TokenService tokens;

    public RcaRepository(JdbcTemplate jdbc, ObjectMapper objectMapper, TokenService tokens) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.tokens = tokens;
    }

    public Cluster createCluster(ClusterCreateRequest request) {
        Instant now = Instant.now();
        Cluster cluster = new Cluster(
            id("cluster"),
            request.name().trim(),
            request.normalizedEnvironment(),
            blankToNull(request.description()),
            ClusterStatus.agent_pending,
            tokens.generateToken(),
            now,
            null
        );
        jdbc.update(
            """
                INSERT INTO clusters
                    (cluster_id, name, environment, description, status, bootstrap_token, created_at, last_seen_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
            cluster.clusterId(),
            cluster.name(),
            cluster.environment(),
            cluster.description(),
            cluster.status().name(),
            cluster.bootstrapToken(),
            timestamp(cluster.createdAt()),
            null
        );
        return cluster;
    }

    public List<Cluster> listClusters() {
        return jdbc.query("SELECT * FROM clusters ORDER BY created_at DESC", this::mapCluster);
    }

    public Optional<Cluster> getCluster(String clusterId) {
        return optionalQuery("SELECT * FROM clusters WHERE cluster_id = ?", this::mapCluster, clusterId);
    }

    @Transactional
    public boolean deleteCluster(String clusterId) {
        if (getCluster(clusterId).isEmpty()) {
            return false;
        }
        jdbc.update("DELETE FROM rca_jobs WHERE cluster_id = ?", clusterId);
        jdbc.update("DELETE FROM evidence_requests WHERE cluster_id = ?", clusterId);
        jdbc.update("DELETE FROM rca_reports WHERE cluster_id = ?", clusterId);
        jdbc.update("DELETE FROM evidence_bundles WHERE cluster_id = ?", clusterId);
        jdbc.update("DELETE FROM node_agents WHERE cluster_id = ?", clusterId);
        return jdbc.update("DELETE FROM clusters WHERE cluster_id = ?", clusterId) == 1;
    }

    @Transactional
    public NodeAgentRegistrationResponse registerAgent(NodeAgentRegisterRequest request) {
        Optional<NodeAgent> existing = getAgent(request.clusterId(), request.nodeName());
        String nodeToken = tokens.generateToken();
        Instant now = Instant.now();
        NodeAgent agent = new NodeAgent(
            existing.map(NodeAgent::agentId).orElseGet(() -> id("agent")),
            request.clusterId(),
            request.nodeName().trim(),
            request.agentVersion().trim(),
            AgentStatus.registered,
            request.collectorsOrEmpty(),
            request.metadataOrEmpty(),
            existing.map(NodeAgent::health).orElse(Map.of()),
            existing.map(NodeAgent::registeredAt).orElse(now),
            existing.map(NodeAgent::lastHeartbeatAt).orElse(null)
        );
        if (existing.isPresent()) {
            jdbc.update(
                """
                    UPDATE node_agents SET node_token_hash = ?, agent_version = ?, status = ?,
                        supported_collectors_json = ?, metadata_json = ?, health_json = ?,
                        registered_at = ?, last_heartbeat_at = ?
                    WHERE agent_id = ?
                    """,
                tokens.hashPassword(nodeToken),
                agent.agentVersion(),
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
                        (agent_id, cluster_id, node_name, node_token_hash, agent_version, status,
                         supported_collectors_json, metadata_json, health_json, registered_at, last_heartbeat_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                agent.agentId(),
                agent.clusterId(),
                agent.nodeName(),
                tokens.hashPassword(nodeToken),
                agent.agentVersion(),
                agent.status().name(),
                json(agent.supportedCollectors()),
                json(agent.metadata()),
                json(agent.health()),
                timestamp(agent.registeredAt()),
                timestamp(agent.lastHeartbeatAt())
            );
        }
        markClusterActive(request.clusterId());
        return new NodeAgentRegistrationResponse(
            agent.agentId(),
            agent.clusterId(),
            agent.nodeName(),
            agent.agentVersion(),
            agent.status(),
            agent.supportedCollectors(),
            agent.metadata(),
            agent.health(),
            agent.registeredAt(),
            agent.lastHeartbeatAt(),
            nodeToken
        );
    }

    @Transactional
    public Optional<NodeAgent> recordAgentHeartbeat(NodeAgentHeartbeatRequest request) {
        Optional<NodeAgent> existing = getAgent(request.clusterId(), request.nodeName());
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        NodeAgent current = existing.get();
        Instant now = Instant.now();
        jdbc.update(
            """
                UPDATE node_agents SET agent_version = ?, status = ?, supported_collectors_json = ?,
                    health_json = ?, last_heartbeat_at = ?
                WHERE cluster_id = ? AND node_name = ?
                """,
            request.agentVersion() == null || request.agentVersion().isBlank()
                ? current.agentVersion()
                : request.agentVersion().trim(),
            request.statusOrDefault().name(),
            json(request.supportedCollectors() == null ? current.supportedCollectors() : request.supportedCollectors()),
            json(request.healthOrEmpty()),
            timestamp(now),
            request.clusterId(),
            request.nodeName()
        );
        markClusterActive(request.clusterId());
        return getAgent(request.clusterId(), request.nodeName());
    }

    public List<NodeAgent> listAgents(String clusterId) {
        return jdbc.query(
            "SELECT * FROM node_agents WHERE cluster_id = ? ORDER BY node_name ASC",
            this::mapAgent,
            clusterId
        );
    }

    public Optional<NodeAgent> getAgent(String clusterId, String nodeName) {
        return optionalQuery(
            "SELECT * FROM node_agents WHERE cluster_id = ? AND node_name = ?",
            this::mapAgent,
            clusterId,
            nodeName
        );
    }

    public boolean verifyAgentNodeToken(String clusterId, String nodeName, String nodeToken) {
        try {
            String hash = jdbc.queryForObject(
                "SELECT node_token_hash FROM node_agents WHERE cluster_id = ? AND node_name = ?",
                String.class,
                clusterId,
                nodeName
            );
            return hash != null && tokens.verifyPassword(nodeToken, hash);
        } catch (EmptyResultDataAccessException exception) {
            return false;
        }
    }

    public EvidenceRequest createEvidenceRequest(EvidenceRequestCreateRequest request) {
        EvidenceRequest evidenceRequest = new EvidenceRequest(
            id("evidence-request"),
            request.clusterId(),
            request.nodeName().trim(),
            request.alertName().trim(),
            request.collectorsOrEmpty(),
            EvidenceRequestStatus.pending,
            request.timeRangeOrEmpty(),
            blankToNull(request.reason()),
            request.contextOrEmpty(),
            null,
            null,
            Instant.now(),
            null
        );
        jdbc.update(
            """
                INSERT INTO evidence_requests
                    (request_id, cluster_id, node_name, alert_name, requested_collectors_json, status,
                     time_range_json, reason, context_json, evidence_id, error_message, created_at, completed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
            evidenceRequest.requestId(),
            evidenceRequest.clusterId(),
            evidenceRequest.nodeName(),
            evidenceRequest.alertName(),
            json(evidenceRequest.requestedCollectors()),
            evidenceRequest.status().name(),
            json(evidenceRequest.timeRange()),
            evidenceRequest.reason(),
            json(evidenceRequest.context()),
            null,
            null,
            timestamp(evidenceRequest.createdAt()),
            null
        );
        return evidenceRequest;
    }

    public List<EvidenceRequest> listEvidenceRequests(
        String clusterId,
        String nodeName,
        EvidenceRequestStatus status,
        Integer limit
    ) {
        StringBuilder sql = new StringBuilder("SELECT * FROM evidence_requests WHERE 1 = 1");
        List<Object> parameters = new ArrayList<>();
        if (clusterId != null) {
            sql.append(" AND cluster_id = ?");
            parameters.add(clusterId);
        }
        if (nodeName != null) {
            sql.append(" AND node_name = ?");
            parameters.add(nodeName);
        }
        if (status != null) {
            sql.append(" AND status = ?");
            parameters.add(status.name());
        }
        sql.append(" ORDER BY created_at ASC");
        if (limit != null) {
            sql.append(" LIMIT ?");
            parameters.add(limit);
        }
        return jdbc.query(sql.toString(), this::mapEvidenceRequest, parameters.toArray());
    }

    public Optional<EvidenceRequest> getEvidenceRequest(String requestId) {
        return optionalQuery(
            "SELECT * FROM evidence_requests WHERE request_id = ?",
            this::mapEvidenceRequest,
            requestId
        );
    }

    public boolean hasPendingEvidenceRequest(String clusterId, String nodeName) {
        Integer count = jdbc.queryForObject(
            """
                SELECT COUNT(*) FROM evidence_requests
                WHERE cluster_id = ? AND node_name = ? AND status = ?
                """,
            Integer.class,
            clusterId,
            nodeName,
            EvidenceRequestStatus.pending.name()
        );
        return count != null && count > 0;
    }

    @Transactional
    public Optional<EvidenceRequest> submitEvidenceResponse(AgentEvidenceSubmitRequest request) {
        Optional<EvidenceRequest> existing = getEvidenceRequest(request.requestId());
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        Instant completedAt = Instant.now();
        String evidenceId = null;
        if (request.statusOrDefault() == EvidenceRequestStatus.completed) {
            evidenceId = id("evidence");
            EvidenceBundle evidence = new EvidenceBundle(
                evidenceId,
                request.clusterId(),
                request.nodeName(),
                existing.get().alertName(),
                completedAt,
                request.collectorsOrEmpty()
            );
            saveEvidence(evidence);
        }
        jdbc.update(
            """
                UPDATE evidence_requests
                SET status = ?, evidence_id = ?, error_message = ?, completed_at = ?
                WHERE request_id = ?
                """,
            request.statusOrDefault().name(),
            evidenceId,
            request.statusOrDefault() == EvidenceRequestStatus.failed ? request.errorMessage() : null,
            timestamp(completedAt),
            request.requestId()
        );
        markClusterActive(request.clusterId());
        return getEvidenceRequest(request.requestId());
    }

    public EvidenceBundle saveEvidence(EvidenceBundle evidence) {
        String evidenceId = evidence.evidenceId() == null ? id("evidence") : evidence.evidenceId();
        jdbc.update(
            """
                INSERT INTO evidence_bundles
                    (evidence_id, cluster_id, node_name, alert_name, collectors_json, collected_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
            evidenceId,
            evidence.clusterId(),
            evidence.nodeName(),
            evidence.alertName(),
            json(evidence.collectors()),
            timestamp(evidence.collectedAt())
        );
        return new EvidenceBundle(
            evidenceId,
            evidence.clusterId(),
            evidence.nodeName(),
            evidence.alertName(),
            evidence.collectedAt(),
            evidence.collectors()
        );
    }

    public Optional<EvidenceBundle> getEvidence(String evidenceId) {
        return optionalQuery(
            "SELECT * FROM evidence_bundles WHERE evidence_id = ?",
            this::mapEvidence,
            evidenceId
        );
    }

    @Transactional
    public RcaJob saveReportAndJob(RcaReport report, RcaJob job) {
        saveReport(report);
        jdbc.update(
            """
                INSERT INTO rca_jobs
                    (job_id, cluster_id, alert_name, node_name, status, report_id, evidence_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
            job.jobId(),
            job.clusterId(),
            job.alertName(),
            job.nodeName(),
            job.status().name(),
            job.reportId(),
            job.evidenceId(),
            timestamp(job.createdAt())
        );
        return job;
    }

    public RcaReport saveReport(RcaReport report) {
        jdbc.update(
            """
                INSERT INTO rca_reports
                    (report_id, cluster_id, status, trigger_json, scope_json, summary_json, evidence_json,
                     root_cause_candidates_json, recommended_actions_json, policy_decisions_json, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
            report.reportId(),
            report.clusterId(),
            report.status().name(),
            json(report.trigger()),
            json(report.scope()),
            json(report.summary()),
            json(report.evidence()),
            json(report.rootCauseCandidates()),
            json(report.recommendedActions()),
            json(report.policyDecisions()),
            timestamp(report.createdAt())
        );
        return report;
    }

    public List<RcaJob> listJobs() {
        return jdbc.query("SELECT * FROM rca_jobs ORDER BY created_at DESC", this::mapJob);
    }

    public Optional<RcaJob> getJob(String jobId) {
        return optionalQuery("SELECT * FROM rca_jobs WHERE job_id = ?", this::mapJob, jobId);
    }

    public List<RcaReport> listReports() {
        return jdbc.query("SELECT * FROM rca_reports ORDER BY created_at DESC", this::mapReport);
    }

    public Optional<RcaReport> getReport(String reportId) {
        return optionalQuery("SELECT * FROM rca_reports WHERE report_id = ?", this::mapReport, reportId);
    }

    public Optional<UserAccount> authenticateUser(String username, String password) {
        Optional<UserRow> row = getUserRowByEmail(username);
        if (row.isEmpty() || !tokens.verifyPassword(password, row.get().passwordHash())) {
            return Optional.empty();
        }
        return Optional.of(row.get().account());
    }

    @Transactional
    public UserAccount ensureDefaultAdmin(String username, String password) {
        String normalized = username.trim().toLowerCase();
        Optional<UserRow> existing = getUserRowByEmail(normalized);
        Instant now = Instant.now();
        if (existing.isPresent()) {
            UserAccount user = existing.get().account();
            jdbc.update(
                """
                    UPDATE user_accounts SET requested_role = ?, role = ?, status = ?,
                        approved_by = COALESCE(approved_by, ?), approved_at = COALESCE(approved_at, ?)
                    WHERE user_id = ?
                    """,
                UserRole.admin.name(),
                UserRole.admin.name(),
                UserStatus.active.name(),
                "system",
                timestamp(now),
                user.userId()
            );
            return getUserById(user.userId()).orElseThrow();
        }

        String userId = jdbc.queryForObject(
            "SELECT COUNT(*) FROM user_accounts WHERE user_id = ?",
            Integer.class,
            "user-admin"
        ) == 0 ? "user-admin" : id("user");
        jdbc.update(
            """
                INSERT INTO user_accounts
                    (user_id, email, full_name, password_hash, requested_role, role, status, reason,
                     approval_note, approved_by, created_at, approved_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
            userId,
            normalized,
            "Administrator",
            tokens.hashPassword(password),
            UserRole.admin.name(),
            UserRole.admin.name(),
            UserStatus.active.name(),
            null,
            null,
            "system",
            timestamp(now),
            timestamp(now)
        );
        return getUserById(userId).orElseThrow();
    }

    public Optional<UserAccount> getUserById(String userId) {
        return optionalQuery(
            "SELECT * FROM user_accounts WHERE user_id = ?",
            (resultSet, rowNumber) -> mapUserRow(resultSet).account(),
            userId
        );
    }

    public Optional<UserAccount> getUserBySessionToken(String token) {
        String tokenHash = tokens.sha256(token);
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                """
                    SELECT u.* FROM user_sessions s
                    JOIN user_accounts u ON u.user_id = s.user_id
                    WHERE s.token_hash = ? AND s.revoked_at IS NULL AND s.expires_at > ? AND u.status = ?
                    """,
                (resultSet, rowNumber) -> mapUserRow(resultSet).account(),
                tokenHash,
                timestamp(Instant.now()),
                UserStatus.active.name()
            ));
        } catch (EmptyResultDataAccessException exception) {
            return Optional.empty();
        }
    }

    public String createUserSession(String userId, Instant expiresAt) {
        String token = tokens.generateToken();
        jdbc.update(
            """
                INSERT INTO user_sessions
                    (session_id, user_id, token_hash, created_at, expires_at, revoked_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
            id("session"),
            userId,
            tokens.sha256(token),
            timestamp(Instant.now()),
            timestamp(expiresAt),
            null
        );
        return token;
    }

    public boolean revokeUserSession(String token) {
        return jdbc.update(
            "UPDATE user_sessions SET revoked_at = ? WHERE token_hash = ? AND revoked_at IS NULL",
            timestamp(Instant.now()),
            tokens.sha256(token)
        ) > 0;
    }

    @Transactional
    public boolean changeUserPassword(String userId, String currentPassword, String newPassword) {
        try {
            String passwordHash = jdbc.queryForObject(
                "SELECT password_hash FROM user_accounts WHERE user_id = ?",
                String.class,
                userId
            );
            if (passwordHash == null || !tokens.verifyPassword(currentPassword, passwordHash)) {
                return false;
            }
            jdbc.update(
                "UPDATE user_accounts SET password_hash = ? WHERE user_id = ?",
                tokens.hashPassword(newPassword),
                userId
            );
            return true;
        } catch (EmptyResultDataAccessException exception) {
            return false;
        }
    }

    private Optional<UserRow> getUserRowByEmail(String email) {
        return optionalQuery(
            "SELECT * FROM user_accounts WHERE email = ?",
            (resultSet, rowNumber) -> mapUserRow(resultSet),
            email.trim().toLowerCase()
        );
    }

    private UserRow mapUserRow(ResultSet resultSet) throws SQLException {
        UserAccount account = new UserAccount(
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
        return new UserRow(account, resultSet.getString("password_hash"));
    }

    private Cluster mapCluster(ResultSet resultSet, int rowNumber) throws SQLException {
        return new Cluster(
            resultSet.getString("cluster_id"),
            resultSet.getString("name"),
            resultSet.getString("environment"),
            resultSet.getString("description"),
            ClusterStatus.valueOf(resultSet.getString("status")),
            resultSet.getString("bootstrap_token"),
            instant(resultSet, "created_at"),
            instant(resultSet, "last_seen_at")
        );
    }

    private NodeAgent mapAgent(ResultSet resultSet, int rowNumber) throws SQLException {
        return new NodeAgent(
            resultSet.getString("agent_id"),
            resultSet.getString("cluster_id"),
            resultSet.getString("node_name"),
            resultSet.getString("agent_version"),
            AgentStatus.valueOf(resultSet.getString("status")),
            read(resultSet.getString("supported_collectors_json"), STRING_LIST, List.of()),
            read(resultSet.getString("metadata_json"), OBJECT_MAP, Map.of()),
            read(resultSet.getString("health_json"), OBJECT_MAP, Map.of()),
            instant(resultSet, "registered_at"),
            instant(resultSet, "last_heartbeat_at")
        );
    }

    private EvidenceRequest mapEvidenceRequest(ResultSet resultSet, int rowNumber) throws SQLException {
        return new EvidenceRequest(
            resultSet.getString("request_id"),
            resultSet.getString("cluster_id"),
            resultSet.getString("node_name"),
            resultSet.getString("alert_name"),
            read(resultSet.getString("requested_collectors_json"), STRING_LIST, List.of()),
            EvidenceRequestStatus.valueOf(resultSet.getString("status")),
            read(resultSet.getString("time_range_json"), OBJECT_MAP, Map.of()),
            resultSet.getString("reason"),
            read(resultSet.getString("context_json"), OBJECT_MAP, Map.of()),
            resultSet.getString("evidence_id"),
            resultSet.getString("error_message"),
            instant(resultSet, "created_at"),
            instant(resultSet, "completed_at")
        );
    }

    private EvidenceBundle mapEvidence(ResultSet resultSet, int rowNumber) throws SQLException {
        return new EvidenceBundle(
            resultSet.getString("evidence_id"),
            resultSet.getString("cluster_id"),
            resultSet.getString("node_name"),
            resultSet.getString("alert_name"),
            instant(resultSet, "collected_at"),
            read(resultSet.getString("collectors_json"), OBJECT_MAP, Map.of())
        );
    }

    private RcaJob mapJob(ResultSet resultSet, int rowNumber) throws SQLException {
        return new RcaJob(
            resultSet.getString("job_id"),
            resultSet.getString("cluster_id"),
            resultSet.getString("alert_name"),
            resultSet.getString("node_name"),
            RcaJobStatus.valueOf(resultSet.getString("status")),
            resultSet.getString("report_id"),
            resultSet.getString("evidence_id"),
            instant(resultSet, "created_at")
        );
    }

    private RcaReport mapReport(ResultSet resultSet, int rowNumber) throws SQLException {
        return new RcaReport(
            resultSet.getString("report_id"),
            resultSet.getString("cluster_id"),
            RcaJobStatus.valueOf(resultSet.getString("status")),
            read(resultSet.getString("trigger_json"), OBJECT_MAP, Map.of()),
            read(resultSet.getString("scope_json"), OBJECT_MAP, Map.of()),
            read(resultSet.getString("summary_json"), RcaModels.RcaSummary.class),
            read(resultSet.getString("evidence_json"), MAP_LIST, List.of()),
            read(resultSet.getString("root_cause_candidates_json"), CANDIDATE_LIST, List.of()),
            read(resultSet.getString("recommended_actions_json"), ACTION_LIST, List.of()),
            read(resultSet.getString("policy_decisions_json"), ACTION_LIST, List.of()),
            instant(resultSet, "created_at")
        );
    }

    private void markClusterActive(String clusterId) {
        jdbc.update(
            "UPDATE clusters SET status = ?, last_seen_at = ? WHERE cluster_id = ?",
            ClusterStatus.active.name(),
            timestamp(Instant.now()),
            clusterId
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

    private <T> T read(String value, Class<T> type) {
        try {
            return objectMapper.readValue(value, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("stored JSON is invalid", exception);
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

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static <E extends Enum<E>> E enumOrNull(Class<E> type, String value) {
        return value == null ? null : Enum.valueOf(type, value);
    }

    private record UserRow(UserAccount account, String passwordHash) {
    }
}
