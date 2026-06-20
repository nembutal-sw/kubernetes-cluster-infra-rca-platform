package io.clusterinfra.rca.webconsole.domain;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class RcaModels {
    private RcaModels() {
    }

    public enum ClusterStatus {
        registered, agent_pending, active
    }

    public enum RcaJobStatus {
        completed, failed
    }

    public enum PolicyLevel {
        AUTO_SAFE, APPROVAL_REQUIRED, GITOPS_PR_ONLY, NEVER_AUTO_EXECUTE, MANUAL_INVESTIGATION
    }

    public enum Confidence {
        low, medium, high
    }

    public enum AgentStatus {
        registered, healthy, degraded, offline
    }

    public enum EvidenceRequestStatus {
        pending, completed, failed
    }

    public enum IncidentStatus {
        open, resolved
    }

    public enum ActionRequestStatus {
        pending_approval, accepted, approved_manual, queued, executing, completed, failed, rejected, blocked
    }

    public enum ActionExecutionStatus {
        pending_approval, queued, leased, completed, failed, expired, rejected
    }

    public enum AnalysisTaskStatus {
        queued, processing, retry_wait, completed, skipped, dead_letter
    }

    public enum UserStatus {
        pending_approval, active, rejected
    }

    public enum UserRole {
        admin, operator, viewer
    }

    public record ClusterCreateRequest(
        @NotBlank @Size(max = 255) String name,
        @Size(max = 64) String environment,
        String description
    ) {
        public String normalizedEnvironment() {
            return environment == null || environment.isBlank() ? "dev" : environment.trim();
        }
    }

    public record Cluster(
        String clusterId,
        String name,
        String environment,
        String description,
        ClusterStatus status,
        String bootstrapToken,
        Instant createdAt,
        Instant lastSeenAt
    ) {
    }

    public record ClusterView(
        String clusterId,
        String name,
        String environment,
        String description,
        ClusterStatus status,
        Instant createdAt,
        Instant lastSeenAt
    ) {
        public static ClusterView from(Cluster cluster) {
            return new ClusterView(
                cluster.clusterId(),
                cluster.name(),
                cluster.environment(),
                cluster.description(),
                cluster.status(),
                cluster.createdAt(),
                cluster.lastSeenAt()
            );
        }
    }

    public record InstallCommandResponse(
        String clusterId,
        String namespace,
        List<String> commands,
        List<String> notes
    ) {
    }

    public record NodeAgentRegisterRequest(
        @NotBlank String clusterId,
        @NotBlank @Size(max = 255) String nodeName,
        @NotBlank String agentToken,
        @NotBlank @Size(max = 64) String agentVersion,
        List<String> supportedCollectors,
        Map<String, Object> metadata
    ) {
        public List<String> collectorsOrEmpty() {
            return supportedCollectors == null ? List.of() : supportedCollectors;
        }

        public Map<String, Object> metadataOrEmpty() {
            return metadata == null ? Map.of() : metadata;
        }
    }

    public record NodeAgentHeartbeatRequest(
        @NotBlank String clusterId,
        @NotBlank @Size(max = 255) String nodeName,
        @NotBlank String agentToken,
        @NotBlank String nodeToken,
        AgentStatus status,
        String agentVersion,
        List<String> supportedCollectors,
        Map<String, Object> health
    ) {
        public AgentStatus statusOrDefault() {
            return status == null ? AgentStatus.healthy : status;
        }

        public Map<String, Object> healthOrEmpty() {
            return health == null ? Map.of() : health;
        }
    }

    public record NodeAgent(
        String agentId,
        String clusterId,
        String nodeName,
        String agentVersion,
        AgentStatus status,
        List<String> supportedCollectors,
        Map<String, Object> metadata,
        Map<String, Object> health,
        Instant registeredAt,
        Instant lastHeartbeatAt
    ) {
    }

    public record NodeAgentRegistrationResponse(
        String agentId,
        String clusterId,
        String nodeName,
        String agentVersion,
        AgentStatus status,
        List<String> supportedCollectors,
        Map<String, Object> metadata,
        Map<String, Object> health,
        Instant registeredAt,
        Instant lastHeartbeatAt,
        String nodeToken
    ) {
    }

    public record EvidenceRequestCreateRequest(
        @NotBlank String clusterId,
        @NotBlank @Size(max = 255) String nodeName,
        @NotBlank @Size(max = 255) String alertName,
        List<String> requestedCollectors,
        Map<String, Object> timeRange,
        String reason,
        Map<String, Object> context
    ) {
        public List<String> collectorsOrEmpty() {
            return requestedCollectors == null ? List.of() : requestedCollectors;
        }

        public Map<String, Object> timeRangeOrEmpty() {
            return timeRange == null ? Map.of() : timeRange;
        }

        public Map<String, Object> contextOrEmpty() {
            return context == null ? Map.of() : context;
        }
    }

    public record EvidenceRequest(
        String requestId,
        String clusterId,
        String nodeName,
        String alertName,
        List<String> requestedCollectors,
        EvidenceRequestStatus status,
        Map<String, Object> timeRange,
        String reason,
        Map<String, Object> context,
        String evidenceId,
        String errorMessage,
        Instant createdAt,
        Instant completedAt
    ) {
    }

    public record ClusterCollectionRequest(
        boolean confirmed,
        String alertName,
        List<String> nodeNames,
        List<String> requestedCollectors,
        String reason,
        Map<String, Object> context
    ) {
        public String alertNameOrDefault() {
            return alertName == null || alertName.isBlank() ? "BackendManualCollection" : alertName.trim();
        }

        public List<String> nodeNamesOrEmpty() {
            return nodeNames == null ? List.of() : nodeNames;
        }

        public List<String> collectorsOrEmpty() {
            return requestedCollectors == null ? List.of() : requestedCollectors;
        }

        public String reasonOrDefault() {
            return reason == null || reason.isBlank() ? "Backend-initiated collection" : reason;
        }

        public Map<String, Object> contextOrEmpty() {
            return context == null ? Map.of() : context;
        }
    }

    public record ClusterCollectionResponse(
        String clusterId,
        List<String> requestedNodes,
        List<EvidenceRequest> createdEvidenceRequests,
        List<String> skippedNodes
    ) {
    }

    public record AgentEvidencePollRequest(
        @NotBlank String clusterId,
        @NotBlank String nodeName,
        @NotBlank String agentToken,
        @NotBlank String nodeToken,
        @Min(1) @Max(100) Integer limit
    ) {
        public int limitOrDefault() {
            return limit == null ? 10 : limit;
        }
    }

    public record AgentEvidenceSubmitRequest(
        @NotBlank String requestId,
        @NotBlank String clusterId,
        @NotBlank String nodeName,
        @NotBlank String agentToken,
        @NotBlank String nodeToken,
        EvidenceRequestStatus status,
        Map<String, Object> collectors,
        String errorMessage
    ) {
        public EvidenceRequestStatus statusOrDefault() {
            return status == null ? EvidenceRequestStatus.completed : status;
        }

        public Map<String, Object> collectorsOrEmpty() {
            return collectors == null ? Map.of() : collectors;
        }
    }

    public record AlertmanagerAlert(
        String status,
        Map<String, String> labels,
        Map<String, String> annotations,
        @JsonAlias("startsAt") Instant startsAt,
        @JsonAlias("endsAt") Instant endsAt,
        @JsonAlias("generatorURL") String generatorUrl
    ) {
        public String statusOrDefault() {
            return status == null || status.isBlank() ? "firing" : status;
        }

        public Map<String, String> labelsOrEmpty() {
            return labels == null ? Map.of() : labels;
        }

        public Map<String, String> annotationsOrEmpty() {
            return annotations == null ? Map.of() : annotations;
        }
    }

    public record AlertmanagerPayload(
        String receiver,
        String status,
        List<AlertmanagerAlert> alerts,
        @JsonAlias("groupLabels") Map<String, String> groupLabels,
        @JsonAlias("commonLabels") Map<String, String> commonLabels,
        @JsonAlias("externalURL") String externalUrl
    ) {
        public List<AlertmanagerAlert> alertsOrEmpty() {
            return alerts == null ? List.of() : alerts;
        }
    }

    public record EvidenceBundle(
        String evidenceId,
        String clusterId,
        String nodeName,
        String alertName,
        Instant collectedAt,
        Map<String, Object> collectors
    ) {
    }

    public record UserLoginRequest(
        @NotBlank @Size(max = 255) String username,
        @NotBlank @Size(max = 256) String password
    ) {
        public String normalizedUsername() {
            return username.trim().toLowerCase();
        }
    }

    public record UserPasswordChangeRequest(
        @NotBlank @Size(max = 256) String currentPassword,
        @NotBlank @Size(min = 8, max = 256) String newPassword
    ) {
    }

    public record UserAccount(
        String userId,
        String email,
        String fullName,
        UserRole requestedRole,
        UserRole role,
        UserStatus status,
        String reason,
        String approvalNote,
        String approvedBy,
        Instant createdAt,
        Instant approvedAt
    ) {
    }

    public record AuthSessionResponse(
        String accessToken,
        String tokenType,
        Instant expiresAt,
        UserAccount user
    ) {
    }

    public record RcaSummary(
        String symptom,
        String mostLikelyCause,
        Confidence confidence
    ) {
    }

    public record RootCauseCandidate(
        String cause,
        Confidence confidence,
        List<String> supportingEvidence
    ) {
    }

    public record RecommendedAction(
        String action,
        PolicyLevel policy,
        String reason,
        String actionKey,
        String source,
        String automationMode,
        boolean automationAllowed,
        boolean requiresApproval,
        boolean reviewRequired,
        List<String> guardrails,
        List<String> riskFactors,
        ActionPlan executionPlan
    ) {
    }

    public record ActionPlan(
        String commandKey,
        Map<String, String> parameters,
        List<String> commandPreview,
        String yamlPatch,
        boolean executable,
        int timeoutSeconds
    ) {
        public Map<String, String> parametersOrEmpty() {
            return parameters == null ? Map.of() : parameters;
        }

        public List<String> commandPreviewOrEmpty() {
            return commandPreview == null ? List.of() : commandPreview;
        }
    }

    public record ActionExecutionRequest(
        boolean confirmed,
        @Size(max = 1000) String note
    ) {
    }

    public record ActionDecisionRequest(
        boolean confirmed,
        @Size(max = 1000) String note
    ) {
    }

    public record Incident(
        String incidentId,
        String clusterId,
        String nodeName,
        String alertName,
        String rootCause,
        IncidentStatus status,
        int occurrenceCount,
        Instant firstSeenAt,
        Instant lastSeenAt,
        String latestEvidenceId,
        String latestReportId
    ) {
    }

    public record ActionRequest(
        String actionRequestId,
        String reportId,
        int actionIndex,
        String actionKey,
        PolicyLevel policy,
        String source,
        ActionRequestStatus status,
        String requestedBy,
        String reviewedBy,
        String requestNote,
        String decisionNote,
        String evidenceRequestId,
        Instant createdAt,
        Instant reviewedAt
    ) {
    }

    public record ActionExecution(
        String executionId,
        String actionRequestId,
        String reportId,
        String clusterId,
        String nodeName,
        String actionKey,
        String commandKey,
        Map<String, String> parameters,
        ActionPlan preview,
        ActionExecutionStatus status,
        int timeoutSeconds,
        String requestedBy,
        String approvedBy,
        String leaseOwner,
        Instant leaseExpiresAt,
        Integer exitCode,
        String stdout,
        String stderr,
        String errorMessage,
        Instant createdAt,
        Instant approvedAt,
        Instant startedAt,
        Instant completedAt
    ) {
    }

    public record ActionApprovalResponse(
        ActionRequest actionRequest,
        ActionExecution actionExecution
    ) {
    }

    public record AgentActionPollRequest(
        @NotBlank String clusterId,
        @NotBlank String nodeName,
        @NotBlank String agentToken,
        @NotBlank String nodeToken,
        @Min(1) @Max(10) Integer limit
    ) {
        public int limitOrDefault() {
            return limit == null ? 1 : limit;
        }
    }

    public record AgentActionResultRequest(
        @NotBlank String executionId,
        @NotBlank String clusterId,
        @NotBlank String nodeName,
        @NotBlank String agentToken,
        @NotBlank String nodeToken,
        @NotBlank String status,
        Integer exitCode,
        @Size(max = 65535) String stdout,
        @Size(max = 65535) String stderr,
        @Size(max = 4000) String errorMessage
    ) {
    }

    public record RealtimeEvent(
        String eventId,
        String evidenceId,
        String clusterId,
        String nodeName,
        String eventType,
        String component,
        String severity,
        Instant observedAt,
        Map<String, Object> payload,
        Instant createdAt
    ) {
    }

    public record AgentRealtimeEvent(
        @NotBlank @Size(max = 64) String eventType,
        @NotBlank @Size(max = 64) String component,
        @NotBlank @Size(max = 32) String severity,
        Instant observedAt,
        Map<String, Object> payload
    ) {
        public Instant observedAtOrNow() {
            return observedAt == null ? Instant.now() : observedAt;
        }

        public Map<String, Object> payloadOrEmpty() {
            return payload == null ? Map.of() : payload;
        }
    }

    public record AgentRealtimeEventBatch(
        @NotBlank String clusterId,
        @NotBlank String nodeName,
        @NotBlank String agentToken,
        @NotBlank String nodeToken,
        @Size(max = 100) List<@Valid AgentRealtimeEvent> events
    ) {
        public List<AgentRealtimeEvent> eventsOrEmpty() {
            return events == null ? List.of() : events;
        }
    }

    public record TimelineNode(
        String id,
        Instant timestamp,
        String component,
        String eventType,
        String severity,
        String title,
        String detail,
        String evidenceId,
        boolean rootTrigger
    ) {
    }

    public record TimelineEdge(
        String source,
        String target,
        String relationship
    ) {
    }

    public record IncidentTimeline(
        String incidentId,
        Instant from,
        Instant to,
        List<TimelineNode> nodes,
        List<TimelineEdge> edges
    ) {
    }

    public record AuditEvent(
        String auditEventId,
        String actorType,
        String actorId,
        String eventType,
        String resourceType,
        String resourceId,
        String outcome,
        Map<String, Object> details,
        Instant createdAt
    ) {
    }

    public record ActionExecutionResponse(
        String reportId,
        int actionIndex,
        String actionKey,
        PolicyLevel policy,
        String status,
        String message,
        boolean executionStarted,
        boolean requiresApproval,
        EvidenceRequest evidenceRequest,
        List<String> guardrails,
        ActionRequest actionRequest,
        ActionExecution actionExecution
    ) {
    }

    public record RcaReport(
        String reportId,
        String clusterId,
        String incidentId,
        RcaJobStatus status,
        Map<String, Object> trigger,
        Map<String, Object> scope,
        RcaSummary summary,
        List<Map<String, Object>> evidence,
        List<RootCauseCandidate> rootCauseCandidates,
        List<RecommendedAction> recommendedActions,
        List<RecommendedAction> policyDecisions,
        Instant createdAt
    ) {
        public RcaReport withIncidentId(String value) {
            return new RcaReport(
                reportId,
                clusterId,
                value,
                status,
                trigger,
                scope,
                summary,
                evidence,
                rootCauseCandidates,
                recommendedActions,
                policyDecisions,
                createdAt
            );
        }
    }

    public record RcaJob(
        String jobId,
        String clusterId,
        String alertName,
        String nodeName,
        RcaJobStatus status,
        String reportId,
        String evidenceId,
        Instant createdAt
    ) {
    }

    public record AnalysisTask(
        String taskId,
        String evidenceId,
        String clusterId,
        String nodeName,
        String alertName,
        String source,
        boolean skipIfHealthy,
        AnalysisTaskStatus status,
        int attemptCount,
        int maxAttempts,
        Instant nextAttemptAt,
        String leaseOwner,
        Instant leaseExpiresAt,
        String lastError,
        String reportId,
        String jobId,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt
    ) {
    }

    public record WebhookIngestResponse(
        int receivedAlerts,
        List<RcaJob> createdJobs,
        List<String> createdReports,
        List<AnalysisTask> queuedAnalysisTasks,
        List<EvidenceRequest> createdEvidenceRequests,
        List<String> skippedAlerts
    ) {
    }
}
