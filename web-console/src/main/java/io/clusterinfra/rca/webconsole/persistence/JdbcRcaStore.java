package io.clusterinfra.rca.webconsole.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.clusterinfra.rca.webconsole.domain.RcaModels;
import io.clusterinfra.rca.webconsole.domain.RcaModels.AgentEvidenceSubmitRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.AgentStatus;
import io.clusterinfra.rca.webconsole.domain.RcaModels.ActionRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.ActionRequestStatus;
import io.clusterinfra.rca.webconsole.domain.RcaModels.ActionExecution;
import io.clusterinfra.rca.webconsole.domain.RcaModels.ActionExecutionStatus;
import io.clusterinfra.rca.webconsole.domain.RcaModels.ActionPlan;
import io.clusterinfra.rca.webconsole.domain.RcaModels.AnalysisTask;
import io.clusterinfra.rca.webconsole.domain.RcaModels.AnalysisTaskStatus;
import io.clusterinfra.rca.webconsole.domain.RcaModels.AuditEvent;
import io.clusterinfra.rca.webconsole.domain.RcaModels.Cluster;
import io.clusterinfra.rca.webconsole.domain.RcaModels.ClusterCreateRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.ClusterStatus;
import io.clusterinfra.rca.webconsole.domain.RcaModels.EvidenceBundle;
import io.clusterinfra.rca.webconsole.domain.RcaModels.EvidenceRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.EvidenceRequestCreateRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.EvidenceRequestStatus;
import io.clusterinfra.rca.webconsole.domain.RcaModels.Incident;
import io.clusterinfra.rca.webconsole.domain.RcaModels.IncidentStatus;
import io.clusterinfra.rca.webconsole.domain.RcaModels.NodeAgent;
import io.clusterinfra.rca.webconsole.domain.RcaModels.NodeAgentHeartbeatRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.NodeAgentRegisterRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.NodeAgentRegistrationResponse;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RcaJob;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RcaJobStatus;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RcaReport;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RecommendedAction;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RootCauseCandidate;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RealtimeEvent;
import io.clusterinfra.rca.webconsole.domain.RcaModels.UserAccount;
import io.clusterinfra.rca.webconsole.domain.RcaModels.UserRole;
import io.clusterinfra.rca.webconsole.domain.RcaModels.UserStatus;
import io.clusterinfra.rca.webconsole.security.TokenService;
import io.clusterinfra.rca.webconsole.security.SensitiveDataRedactor;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
public class JdbcRcaStore {
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {
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

    public JdbcRcaStore(JdbcTemplate jdbc, ObjectMapper objectMapper, TokenService tokens) {
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
        jdbc.update("DELETE FROM rca_analysis_tasks WHERE cluster_id = ?", clusterId);
        jdbc.update("DELETE FROM action_executions WHERE cluster_id = ?", clusterId);
        jdbc.update(
            """
                DELETE FROM action_requests
                WHERE report_id IN (SELECT report_id FROM rca_reports WHERE cluster_id = ?)
                """,
            clusterId
        );
        jdbc.update("DELETE FROM rca_jobs WHERE cluster_id = ?", clusterId);
        jdbc.update("DELETE FROM evidence_requests WHERE cluster_id = ?", clusterId);
        jdbc.update("DELETE FROM rca_reports WHERE cluster_id = ?", clusterId);
        jdbc.update("DELETE FROM incidents WHERE cluster_id = ?", clusterId);
        jdbc.update("DELETE FROM realtime_events WHERE cluster_id = ?", clusterId);
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
            request.protocolVersionOrDefault(),
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
                    UPDATE node_agents SET node_token_hash = ?, agent_version = ?,
                        agent_protocol_version = ?, status = ?,
                        supported_collectors_json = ?, metadata_json = ?, health_json = ?,
                        registered_at = ?, last_heartbeat_at = ?
                    WHERE agent_id = ?
                    """,
                tokens.hashPassword(nodeToken),
                agent.agentVersion(),
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
                         agent_protocol_version, status,
                         supported_collectors_json, metadata_json, health_json, registered_at, last_heartbeat_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                agent.agentId(),
                agent.clusterId(),
                agent.nodeName(),
                tokens.hashPassword(nodeToken),
                agent.agentVersion(),
                agent.agentProtocolVersion(),
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

    public List<NodeAgent> listAgents() {
        return jdbc.query(
            "SELECT * FROM node_agents ORDER BY cluster_id, node_name",
            this::mapAgent
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
        return submitEvidenceResponse(request, 5);
    }

    @Transactional
    public Optional<EvidenceRequest> submitEvidenceResponse(
        AgentEvidenceSubmitRequest request,
        int analysisMaxAttempts
    ) {
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
            enqueueAnalysisTask(
                evidence,
                "agent_evidence",
                "scheduled_monitoring".equals(existing.get().context().get("trigger")),
                analysisMaxAttempts
            );
        }
        jdbc.update(
            """
                UPDATE evidence_requests
                SET status = ?, evidence_id = ?, error_message = ?, completed_at = ?
                WHERE request_id = ?
                """,
            request.statusOrDefault().name(),
            evidenceId,
            request.statusOrDefault() == EvidenceRequestStatus.failed
                ? SensitiveDataRedactor.redactText(request.errorMessage())
                : null,
            timestamp(completedAt),
            request.requestId()
        );
        markClusterActive(request.clusterId());
        return getEvidenceRequest(request.requestId());
    }

    @Transactional
    public AnalysisTask saveEvidenceAndEnqueue(
        EvidenceBundle evidence,
        String source,
        boolean skipIfHealthy,
        int maxAttempts
    ) {
        EvidenceBundle saved = saveEvidence(evidence);
        return enqueueAnalysisTask(saved, source, skipIfHealthy, maxAttempts);
    }

    public EvidenceBundle saveEvidence(EvidenceBundle evidence) {
        String evidenceId = evidence.evidenceId() == null ? id("evidence") : evidence.evidenceId();
        Map<String, Object> redactedCollectors = SensitiveDataRedactor.redactMap(evidence.collectors());
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
            json(redactedCollectors),
            timestamp(evidence.collectedAt())
        );
        return new EvidenceBundle(
            evidenceId,
            evidence.clusterId(),
            evidence.nodeName(),
            evidence.alertName(),
            evidence.collectedAt(),
            redactedCollectors
        );
    }

    public AnalysisTask enqueueAnalysisTask(
        EvidenceBundle evidence,
        String source,
        boolean skipIfHealthy,
        int maxAttempts
    ) {
        Optional<AnalysisTask> existing = getAnalysisTaskByEvidenceId(evidence.evidenceId());
        if (existing.isPresent()) {
            return existing.get();
        }
        Instant now = databaseInstant();
        AnalysisTask task = new AnalysisTask(
            id("analysis"),
            evidence.evidenceId(),
            evidence.clusterId(),
            evidence.nodeName(),
            evidence.alertName(),
            blankToNull(source) == null ? "unknown" : source.trim(),
            skipIfHealthy,
            AnalysisTaskStatus.queued,
            0,
            Math.max(1, Math.min(maxAttempts, 20)),
            now,
            null,
            null,
            null,
            null,
            null,
            now,
            null,
            null
        );
        jdbc.update(
            """
                INSERT INTO rca_analysis_tasks
                    (task_id, evidence_id, cluster_id, node_name, alert_name, source, skip_if_healthy,
                     status, attempt_count, max_attempts, next_attempt_at, lease_owner, lease_expires_at,
                     last_error, report_id, job_id, created_at, started_at, completed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
            task.taskId(),
            task.evidenceId(),
            task.clusterId(),
            task.nodeName(),
            task.alertName(),
            task.source(),
            task.skipIfHealthy() ? 1 : 0,
            task.status().name(),
            task.attemptCount(),
            task.maxAttempts(),
            timestamp(task.nextAttemptAt()),
            null,
            null,
            null,
            null,
            null,
            timestamp(task.createdAt()),
            null,
            null
        );
        return task;
    }

    public Optional<AnalysisTask> getAnalysisTask(String taskId) {
        return optionalQuery(
            "SELECT * FROM rca_analysis_tasks WHERE task_id = ?",
            this::mapAnalysisTask,
            taskId
        );
    }

    public Optional<AnalysisTask> getAnalysisTaskByEvidenceId(String evidenceId) {
        return optionalQuery(
            "SELECT * FROM rca_analysis_tasks WHERE evidence_id = ?",
            this::mapAnalysisTask,
            evidenceId
        );
    }

    public List<AnalysisTask> listAnalysisTasks(AnalysisTaskStatus status, Integer limit) {
        int safeLimit = limit == null ? 200 : Math.max(1, Math.min(limit, 1000));
        if (status == null) {
            return jdbc.query(
                "SELECT * FROM rca_analysis_tasks ORDER BY created_at DESC LIMIT ?",
                this::mapAnalysisTask,
                safeLimit
            );
        }
        return jdbc.query(
            "SELECT * FROM rca_analysis_tasks WHERE status = ? ORDER BY created_at DESC LIMIT ?",
            this::mapAnalysisTask,
            status.name(),
            safeLimit
        );
    }

    public long countAnalysisTasks(AnalysisTaskStatus status) {
        Long count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM rca_analysis_tasks WHERE status = ?",
            Long.class,
            status.name()
        );
        return count == null ? 0 : count;
    }

    @Transactional
    public List<AnalysisTask> claimAnalysisTasks(
        String leaseOwner,
        int limit,
        Instant now,
        Instant leaseExpiresAt
    ) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        List<String> candidates = jdbc.queryForList(
            """
                SELECT task_id FROM rca_analysis_tasks
                WHERE ((status IN (?, ?) AND next_attempt_at <= ?)
                    OR (status = ? AND lease_expires_at < ?))
                ORDER BY next_attempt_at, created_at
                LIMIT ?
                """,
            String.class,
            AnalysisTaskStatus.queued.name(),
            AnalysisTaskStatus.retry_wait.name(),
            timestamp(now),
            AnalysisTaskStatus.processing.name(),
            timestamp(now),
            safeLimit * 3
        );
        List<AnalysisTask> claimed = new ArrayList<>();
        for (String taskId : candidates) {
            int updated = jdbc.update(
                """
                    UPDATE rca_analysis_tasks
                    SET status = ?, attempt_count = attempt_count + 1, lease_owner = ?,
                        lease_expires_at = ?, started_at = COALESCE(started_at, ?), last_error = NULL
                    WHERE task_id = ? AND ((status IN (?, ?) AND next_attempt_at <= ?)
                        OR (status = ? AND lease_expires_at < ?))
                    """,
                AnalysisTaskStatus.processing.name(),
                leaseOwner,
                timestamp(leaseExpiresAt),
                timestamp(now),
                taskId,
                AnalysisTaskStatus.queued.name(),
                AnalysisTaskStatus.retry_wait.name(),
                timestamp(now),
                AnalysisTaskStatus.processing.name(),
                timestamp(now)
            );
            if (updated == 1) {
                getAnalysisTask(taskId).ifPresent(claimed::add);
            }
            if (claimed.size() >= safeLimit) {
                break;
            }
        }
        return claimed;
    }

    public boolean completeAnalysisTask(
        String taskId,
        String leaseOwner,
        AnalysisTaskStatus status,
        String reportId,
        String jobId,
        Instant completedAt
    ) {
        if (status != AnalysisTaskStatus.completed && status != AnalysisTaskStatus.skipped) {
            throw new IllegalArgumentException("analysis task completion status is invalid");
        }
        return jdbc.update(
            """
                UPDATE rca_analysis_tasks
                SET status = ?, report_id = ?, job_id = ?, lease_owner = NULL, lease_expires_at = NULL,
                    next_attempt_at = ?, completed_at = ?
                WHERE task_id = ? AND status = ? AND lease_owner = ?
                """,
            status.name(),
            reportId,
            jobId,
            timestamp(completedAt),
            timestamp(completedAt),
            taskId,
            AnalysisTaskStatus.processing.name(),
            leaseOwner
        ) == 1;
    }

    public boolean failAnalysisTask(
        AnalysisTask task,
        String leaseOwner,
        String error,
        Instant nextAttemptAt
    ) {
        boolean exhausted = task.attemptCount() >= task.maxAttempts();
        AnalysisTaskStatus status = exhausted
            ? AnalysisTaskStatus.dead_letter
            : AnalysisTaskStatus.retry_wait;
        Instant completedAt = exhausted ? databaseInstant() : null;
        return jdbc.update(
            """
                UPDATE rca_analysis_tasks
                SET status = ?, next_attempt_at = ?, lease_owner = NULL, lease_expires_at = NULL,
                    last_error = ?, completed_at = ?
                WHERE task_id = ? AND status = ? AND lease_owner = ?
                """,
            status.name(),
            timestamp(nextAttemptAt),
            error,
            timestamp(completedAt),
            task.taskId(),
            AnalysisTaskStatus.processing.name(),
            leaseOwner
        ) == 1;
    }

    public Optional<AnalysisTask> retryAnalysisTask(String taskId) {
        Instant now = databaseInstant();
        int updated = jdbc.update(
            """
                UPDATE rca_analysis_tasks
                SET status = ?, attempt_count = 0, next_attempt_at = ?, lease_owner = NULL,
                    lease_expires_at = NULL, last_error = NULL, started_at = NULL, completed_at = NULL
                WHERE task_id = ? AND status = ?
                """,
            AnalysisTaskStatus.queued.name(),
            timestamp(now),
            taskId,
            AnalysisTaskStatus.dead_letter.name()
        );
        return updated == 0 ? Optional.empty() : getAnalysisTask(taskId);
    }

    public Optional<EvidenceBundle> getEvidence(String evidenceId) {
        return optionalQuery(
            "SELECT * FROM evidence_bundles WHERE evidence_id = ?",
            this::mapEvidence,
            evidenceId
        );
    }

    public List<EvidenceBundle> listEvidenceForNodeWindow(
        String clusterId,
        String nodeName,
        Instant from,
        Instant to
    ) {
        return jdbc.query(
            """
                SELECT * FROM evidence_bundles
                WHERE cluster_id = ? AND node_name = ? AND collected_at BETWEEN ? AND ?
                ORDER BY collected_at, evidence_id
                """,
            this::mapEvidence,
            clusterId,
            nodeName,
            timestamp(from),
            timestamp(to)
        );
    }

    public RealtimeEvent saveRealtimeEvent(RealtimeEvent event) {
        jdbc.update(
            """
                INSERT INTO realtime_events
                    (event_id, evidence_id, cluster_id, node_name, event_type, component,
                     severity, observed_at, payload_json, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
            event.eventId(),
            event.evidenceId(),
            event.clusterId(),
            event.nodeName(),
            event.eventType(),
            event.component(),
            event.severity(),
            timestamp(event.observedAt()),
            json(event.payload()),
            timestamp(event.createdAt())
        );
        return event;
    }

    public List<RealtimeEvent> listRealtimeEventsForNodeWindow(
        String clusterId,
        String nodeName,
        Instant from,
        Instant to
    ) {
        return jdbc.query(
            """
                SELECT * FROM realtime_events
                WHERE cluster_id = ? AND node_name = ? AND observed_at BETWEEN ? AND ?
                ORDER BY observed_at, event_id
                """,
            this::mapRealtimeEvent,
            clusterId,
            nodeName,
            timestamp(from),
            timestamp(to)
        );
    }

    @Transactional
    public RcaJob saveReportAndJob(RcaReport report, RcaJob job) {
        saveReport(report);
        insertJob(job);
        return job;
    }

    @Transactional
    public RcaJob saveCorrelatedReportAndJob(
        RcaReport report,
        RcaJob job,
        String dedupKey,
        EvidenceBundle evidence
    ) {
        jdbc.queryForObject(
            "SELECT cluster_id FROM clusters WHERE cluster_id = ? FOR UPDATE",
            String.class,
            evidence.clusterId()
        );
        Incident incident = findIncidentByDedupKey(dedupKey).orElseGet(() -> {
            Incident created = new Incident(
                id("incident"),
                evidence.clusterId(),
                evidence.nodeName(),
                evidence.alertName(),
                report.summary().mostLikelyCause(),
                IncidentStatus.open,
                0,
                evidence.collectedAt(),
                evidence.collectedAt(),
                evidence.evidenceId(),
                null
            );
            jdbc.update(
                """
                    INSERT INTO incidents
                        (incident_id, dedup_key, cluster_id, node_name, alert_name, root_cause, status,
                         occurrence_count, first_seen_at, last_seen_at, latest_evidence_id, latest_report_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                created.incidentId(),
                dedupKey,
                created.clusterId(),
                created.nodeName(),
                created.alertName(),
                created.rootCause(),
                created.status().name(),
                created.occurrenceCount(),
                timestamp(created.firstSeenAt()),
                timestamp(created.lastSeenAt()),
                created.latestEvidenceId(),
                null
            );
            return created;
        });

        Incident locked = optionalQuery(
            "SELECT * FROM incidents WHERE incident_id = ? FOR UPDATE",
            this::mapIncident,
            incident.incidentId()
        ).orElseThrow();
        if (locked.latestReportId() != null) {
            jdbc.update(
                """
                    UPDATE incidents SET occurrence_count = occurrence_count + 1,
                        last_seen_at = ?, latest_evidence_id = ?
                    WHERE incident_id = ?
                    """,
                timestamp(evidence.collectedAt()),
                evidence.evidenceId(),
                locked.incidentId()
            );
            return getLatestJobForIncident(locked.incidentId()).orElseThrow();
        }

        RcaReport correlatedReport = report.withIncidentId(locked.incidentId());
        saveReport(correlatedReport);
        insertJob(job);
        jdbc.update(
            """
                UPDATE incidents SET occurrence_count = 1, last_seen_at = ?, latest_evidence_id = ?,
                    latest_report_id = ?
                WHERE incident_id = ?
                """,
            timestamp(evidence.collectedAt()),
            evidence.evidenceId(),
            report.reportId(),
            locked.incidentId()
        );
        return job;
    }

    private void insertJob(RcaJob job) {
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
    }

    public RcaReport saveReport(RcaReport report) {
        jdbc.update(
            """
                INSERT INTO rca_reports
                    (report_id, cluster_id, incident_id, status, trigger_json, scope_json, summary_json, evidence_json,
                     root_cause_candidates_json, recommended_actions_json, policy_decisions_json, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
            report.reportId(),
            report.clusterId(),
            report.incidentId(),
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

    public List<Incident> listIncidents(String clusterId) {
        if (clusterId == null || clusterId.isBlank()) {
            return jdbc.query("SELECT * FROM incidents ORDER BY last_seen_at DESC", this::mapIncident);
        }
        return jdbc.query(
            "SELECT * FROM incidents WHERE cluster_id = ? ORDER BY last_seen_at DESC",
            this::mapIncident,
            clusterId
        );
    }

    public Optional<Incident> getIncident(String incidentId) {
        return optionalQuery("SELECT * FROM incidents WHERE incident_id = ?", this::mapIncident, incidentId);
    }

    public Optional<Incident> updateIncidentStatus(String incidentId, IncidentStatus status) {
        int updated = status == IncidentStatus.resolved
            ? jdbc.update(
                "UPDATE incidents SET status = ?, dedup_key = ?, last_seen_at = ? WHERE incident_id = ?",
                status.name(),
                id("resolved"),
                timestamp(Instant.now()),
                incidentId
            )
            : jdbc.update(
                "UPDATE incidents SET status = ?, last_seen_at = ? WHERE incident_id = ?",
                status.name(),
                timestamp(Instant.now()),
                incidentId
            );
        return updated == 0 ? Optional.empty() : getIncident(incidentId);
    }

    public Optional<Incident> findIncidentByDedupKey(String dedupKey) {
        return optionalQuery("SELECT * FROM incidents WHERE dedup_key = ?", this::mapIncident, dedupKey);
    }

    public Optional<RcaJob> getLatestJobForIncident(String incidentId) {
        return optionalQuery(
            """
                SELECT j.* FROM rca_jobs j
                JOIN rca_reports r ON r.report_id = j.report_id
                WHERE r.incident_id = ?
                ORDER BY j.created_at DESC
                LIMIT 1
                """,
            this::mapJob,
            incidentId
        );
    }

    public ActionRequest createActionRequest(
        String reportId,
        int actionIndex,
        String actionKey,
        RcaModels.PolicyLevel policy,
        String source,
        ActionRequestStatus status,
        String requestedBy,
        String requestNote,
        String evidenceRequestId
    ) {
        ActionRequest request = new ActionRequest(
            id("action-request"),
            reportId,
            actionIndex,
            actionKey,
            policy,
            source,
            status,
            requestedBy,
            null,
            blankToNull(requestNote),
            null,
            evidenceRequestId,
            databaseInstant(),
            null
        );
        jdbc.update(
            """
                INSERT INTO action_requests
                    (action_request_id, report_id, action_index, action_key, policy, source, status,
                     requested_by, reviewed_by, request_note, decision_note, evidence_request_id,
                     created_at, reviewed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
            request.actionRequestId(),
            request.reportId(),
            request.actionIndex(),
            request.actionKey(),
            request.policy().name(),
            request.source(),
            request.status().name(),
            request.requestedBy(),
            null,
            request.requestNote(),
            null,
            request.evidenceRequestId(),
            timestamp(request.createdAt()),
            null
        );
        return request;
    }

    @Transactional
    public Optional<ActionRequest> decideActionRequest(
        String actionRequestId,
        ActionRequestStatus status,
        String reviewedBy,
        String decisionNote
    ) {
        int updated = jdbc.update(
            """
                UPDATE action_requests SET status = ?, reviewed_by = ?, decision_note = ?, reviewed_at = ?
                WHERE action_request_id = ? AND status = ?
                """,
            status.name(),
            reviewedBy,
            blankToNull(decisionNote),
            timestamp(Instant.now()),
            actionRequestId,
            ActionRequestStatus.pending_approval.name()
        );
        return updated == 0 ? Optional.empty() : getActionRequest(actionRequestId);
    }

    public Optional<ActionRequest> getActionRequest(String actionRequestId) {
        return optionalQuery(
            "SELECT * FROM action_requests WHERE action_request_id = ?",
            this::mapActionRequest,
            actionRequestId
        );
    }

    @Transactional
    public Optional<ActionRequest> completeManualActionRequest(String actionRequestId) {
        int updated = jdbc.update(
            "UPDATE action_requests SET status = ? WHERE action_request_id = ? AND status = ?",
            ActionRequestStatus.completed.name(),
            actionRequestId,
            ActionRequestStatus.approved_manual.name()
        );
        return updated == 0 ? Optional.empty() : getActionRequest(actionRequestId);
    }

    public List<ActionRequest> listActionRequests(String reportId) {
        if (reportId == null || reportId.isBlank()) {
            return jdbc.query("SELECT * FROM action_requests ORDER BY created_at DESC", this::mapActionRequest);
        }
        return jdbc.query(
            "SELECT * FROM action_requests WHERE report_id = ? ORDER BY created_at DESC",
            this::mapActionRequest,
            reportId
        );
    }

    public Optional<ActionExecution> getActionExecution(String executionId) {
        return optionalQuery(
            "SELECT * FROM action_executions WHERE execution_id = ?",
            this::mapActionExecution,
            executionId
        );
    }

    public Optional<ActionExecution> getActionExecutionByRequest(String actionRequestId) {
        return optionalQuery(
            "SELECT * FROM action_executions WHERE action_request_id = ?",
            this::mapActionExecution,
            actionRequestId
        );
    }

    public List<ActionExecution> listActionExecutions(String reportId) {
        if (reportId == null || reportId.isBlank()) {
            return jdbc.query(
                "SELECT * FROM action_executions ORDER BY created_at DESC",
                this::mapActionExecution
            );
        }
        return jdbc.query(
            "SELECT * FROM action_executions WHERE report_id = ? ORDER BY created_at DESC",
            this::mapActionExecution,
            reportId
        );
    }

    public AuditEvent saveAuditEvent(
        String actorType,
        String actorId,
        String eventType,
        String resourceType,
        String resourceId,
        String outcome,
        Map<String, Object> details
    ) {
        AuditEvent event = new AuditEvent(
            id("audit"),
            actorType,
            actorId,
            eventType,
            resourceType,
            resourceId,
            outcome,
            details == null ? Map.of() : details,
            databaseInstant()
        );
        jdbc.update(
            """
                INSERT INTO audit_events
                    (audit_event_id, actor_type, actor_id, event_type, resource_type,
                     resource_id, outcome, details_json, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
            event.auditEventId(),
            event.actorType(),
            event.actorId(),
            event.eventType(),
            event.resourceType(),
            event.resourceId(),
            event.outcome(),
            json(event.details()),
            timestamp(event.createdAt())
        );
        return event;
    }

    public List<AuditEvent> listAuditEvents(Integer limit) {
        int safeLimit = limit == null ? 200 : Math.max(1, Math.min(limit, 1000));
        return jdbc.query(
            "SELECT * FROM audit_events ORDER BY created_at DESC LIMIT ?",
            this::mapAuditEvent,
            safeLimit
        );
    }

    public int deleteAuditEventsBefore(Instant cutoff) {
        return jdbc.update("DELETE FROM audit_events WHERE created_at < ?", timestamp(cutoff));
    }

    public int deleteExpiredSessions(Instant cutoff) {
        return jdbc.update("DELETE FROM user_sessions WHERE expires_at < ?", timestamp(cutoff));
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
            resultSet.getString("agent_protocol_version"),
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

    private Incident mapIncident(ResultSet resultSet, int rowNumber) throws SQLException {
        return new Incident(
            resultSet.getString("incident_id"),
            resultSet.getString("cluster_id"),
            resultSet.getString("node_name"),
            resultSet.getString("alert_name"),
            resultSet.getString("root_cause"),
            IncidentStatus.valueOf(resultSet.getString("status")),
            resultSet.getInt("occurrence_count"),
            instant(resultSet, "first_seen_at"),
            instant(resultSet, "last_seen_at"),
            resultSet.getString("latest_evidence_id"),
            resultSet.getString("latest_report_id")
        );
    }

    private AnalysisTask mapAnalysisTask(ResultSet resultSet, int rowNumber) throws SQLException {
        return new AnalysisTask(
            resultSet.getString("task_id"),
            resultSet.getString("evidence_id"),
            resultSet.getString("cluster_id"),
            resultSet.getString("node_name"),
            resultSet.getString("alert_name"),
            resultSet.getString("source"),
            resultSet.getInt("skip_if_healthy") != 0,
            AnalysisTaskStatus.valueOf(resultSet.getString("status")),
            resultSet.getInt("attempt_count"),
            resultSet.getInt("max_attempts"),
            instant(resultSet, "next_attempt_at"),
            resultSet.getString("lease_owner"),
            instant(resultSet, "lease_expires_at"),
            resultSet.getString("last_error"),
            resultSet.getString("report_id"),
            resultSet.getString("job_id"),
            instant(resultSet, "created_at"),
            instant(resultSet, "started_at"),
            instant(resultSet, "completed_at")
        );
    }

    private ActionRequest mapActionRequest(ResultSet resultSet, int rowNumber) throws SQLException {
        return new ActionRequest(
            resultSet.getString("action_request_id"),
            resultSet.getString("report_id"),
            resultSet.getInt("action_index"),
            resultSet.getString("action_key"),
            RcaModels.PolicyLevel.valueOf(resultSet.getString("policy")),
            resultSet.getString("source"),
            ActionRequestStatus.valueOf(resultSet.getString("status")),
            resultSet.getString("requested_by"),
            resultSet.getString("reviewed_by"),
            resultSet.getString("request_note"),
            resultSet.getString("decision_note"),
            resultSet.getString("evidence_request_id"),
            instant(resultSet, "created_at"),
            instant(resultSet, "reviewed_at")
        );
    }

    private ActionExecution mapActionExecution(ResultSet resultSet, int rowNumber) throws SQLException {
        return new ActionExecution(
            resultSet.getString("execution_id"),
            resultSet.getString("action_request_id"),
            resultSet.getString("report_id"),
            resultSet.getString("cluster_id"),
            resultSet.getString("node_name"),
            resultSet.getString("action_key"),
            resultSet.getString("command_key"),
            read(resultSet.getString("parameters_json"), STRING_MAP, Map.of()),
            read(resultSet.getString("preview_json"), ActionPlan.class),
            ActionExecutionStatus.valueOf(resultSet.getString("status")),
            resultSet.getInt("timeout_seconds"),
            resultSet.getString("requested_by"),
            resultSet.getString("approved_by"),
            resultSet.getString("lease_owner"),
            instant(resultSet, "lease_expires_at"),
            (Integer) resultSet.getObject("exit_code"),
            resultSet.getString("stdout_text"),
            resultSet.getString("stderr_text"),
            resultSet.getString("error_message"),
            instant(resultSet, "created_at"),
            instant(resultSet, "approved_at"),
            instant(resultSet, "started_at"),
            instant(resultSet, "completed_at")
        );
    }

    private RealtimeEvent mapRealtimeEvent(ResultSet resultSet, int rowNumber) throws SQLException {
        return new RealtimeEvent(
            resultSet.getString("event_id"),
            resultSet.getString("evidence_id"),
            resultSet.getString("cluster_id"),
            resultSet.getString("node_name"),
            resultSet.getString("event_type"),
            resultSet.getString("component"),
            resultSet.getString("severity"),
            instant(resultSet, "observed_at"),
            read(resultSet.getString("payload_json"), OBJECT_MAP, Map.of()),
            instant(resultSet, "created_at")
        );
    }

    private AuditEvent mapAuditEvent(ResultSet resultSet, int rowNumber) throws SQLException {
        return new AuditEvent(
            resultSet.getString("audit_event_id"),
            resultSet.getString("actor_type"),
            resultSet.getString("actor_id"),
            resultSet.getString("event_type"),
            resultSet.getString("resource_type"),
            resultSet.getString("resource_id"),
            resultSet.getString("outcome"),
            read(resultSet.getString("details_json"), OBJECT_MAP, Map.of()),
            instant(resultSet, "created_at")
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
            resultSet.getString("incident_id"),
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

    private Instant databaseInstant() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    private static String id(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String limitedText(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private static <E extends Enum<E>> E enumOrNull(Class<E> type, String value) {
        return value == null ? null : Enum.valueOf(type, value);
    }

    private record UserRow(UserAccount account, String passwordHash) {
    }
}
