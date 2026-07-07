package io.clusterinfra.rca.webconsole.domain;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
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

    public enum AgentHealthStatus {
        healthy, stale, offline, unauthorized, version_mismatch, collector_degraded
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
        admin, operator, viewer, auditor, approver
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
        @Size(max = 32) String agentProtocolVersion,
        List<String> supportedCollectors,
        Map<String, Object> metadata
    ) {
        public NodeAgentRegisterRequest(
            String clusterId,
            String nodeName,
            String agentToken,
            String agentVersion,
            List<String> supportedCollectors,
            Map<String, Object> metadata
        ) {
            this(clusterId, nodeName, agentToken, agentVersion, "1", supportedCollectors, metadata);
        }

        public String protocolVersionOrDefault() {
            return agentProtocolVersion == null || agentProtocolVersion.isBlank()
                ? "1"
                : agentProtocolVersion.trim();
        }

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
        @Size(max = 32) String agentProtocolVersion,
        List<String> supportedCollectors,
        Map<String, Object> health
    ) {
        public NodeAgentHeartbeatRequest(
            String clusterId,
            String nodeName,
            String agentToken,
            String nodeToken,
            AgentStatus status,
            String agentVersion,
            List<String> supportedCollectors,
            Map<String, Object> health
        ) {
            this(
                clusterId,
                nodeName,
                agentToken,
                nodeToken,
                status,
                agentVersion,
                "1",
                supportedCollectors,
                health
            );
        }

        public AgentStatus statusOrDefault() {
            return status == null ? AgentStatus.healthy : status;
        }

        public String protocolVersionOrDefault() {
            return agentProtocolVersion == null || agentProtocolVersion.isBlank()
                ? "1"
                : agentProtocolVersion.trim();
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
        String agentProtocolVersion,
        AgentStatus status,
        List<String> supportedCollectors,
        Map<String, Object> metadata,
        Map<String, Object> health,
        Instant registeredAt,
        Instant lastHeartbeatAt
    ) {
        public NodeAgent(
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
            this(
                agentId,
                clusterId,
                nodeName,
                agentVersion,
                "1",
                status,
                supportedCollectors,
                metadata,
                health,
                registeredAt,
                lastHeartbeatAt
            );
        }
    }

    public record AgentHealthView(
        String agentId,
        String clusterId,
        String nodeName,
        String agentVersion,
        String agentProtocolVersion,
        AgentHealthStatus healthStatus,
        AgentStatus reportedStatus,
        List<String> supportedCollectors,
        Map<String, Object> health,
        Instant registeredAt,
        Instant lastHeartbeatAt,
        long heartbeatAgeSeconds,
        String platformProtocolVersion,
        List<String> reasons
    ) {
    }

    public record NodeAgentRegistrationResponse(
        String agentId,
        String clusterId,
        String nodeName,
        String agentVersion,
        String agentProtocolVersion,
        AgentStatus status,
        List<String> supportedCollectors,
        Map<String, Object> metadata,
        Map<String, Object> health,
        Instant registeredAt,
        Instant lastHeartbeatAt,
        String nodeToken
    ) {
    }

    public record PlatformInfo(
        String platformVersion,
        String apiVersion,
        String agentProtocolVersion,
        String minimumSupportedAgentProtocolVersion,
        String minimumSupportedAgentVersion,
        ExportSecurityInfo exportSecurity,
        LlmConfigurationInfo llm,
        NotificationConfigurationInfo notification
    ) {
    }

    public record NotificationConfigurationInfo(
        boolean enabled,
        boolean slackConfigured,
        boolean webhookConfigured,
        boolean webhookTokenConfigured,
        String minimumSeverity,
        int maxAttempts,
        int timeoutSeconds,
        List<String> channels
    ) {
    }

    public record NotificationTestRequest(
        boolean confirmed
    ) {
    }

    public record NotificationDeliveryResult(
        String channel,
        String outcome,
        int attempts,
        Integer statusCode,
        String error
    ) {
    }

    public record NotificationTestResponse(
        String outcome,
        String message,
        List<NotificationDeliveryResult> results
    ) {
    }

    public record ExportSecurityInfo(
        long maxBundleBytes,
        String hashAlgorithm,
        boolean bundleSignatureEnabled,
        String bundleSignatureAlgorithm,
        String bundleSignatureKeyId,
        String offlineVerifier
    ) {
    }

    public record LlmConfigurationInfo(
        boolean enabled,
        String provider,
        String model,
        String springAiChatModel,
        boolean credentialRequired,
        boolean credentialConfigured,
        String credentialProperty,
        String credentialEnv,
        boolean baseUrlRequired,
        boolean baseUrlConfigured,
        String baseUrlProperty,
        String baseUrlEnv,
        int timeoutSeconds,
        int maxAttempts,
        int maxOutputTokens,
        int failureThreshold,
        int cooldownSeconds
    ) {
    }

    public record LlmDiagnosticCheck(
        String key,
        String status,
        String message,
        String remediation
    ) {
    }

    public record LlmDiagnosticResponse(
        String outcome,
        LlmConfigurationInfo configuration,
        List<LlmDiagnosticCheck> checks
    ) {
    }

    public record LlmProviderSetupOption(
        String provider,
        String displayName,
        String springAiChatModel,
        String credentialEnv,
        String baseUrlEnv,
        boolean credentialRequired,
        boolean baseUrlRequired,
        List<String> modelExamples,
        String note
    ) {
    }

    public record LlmSetupGuideResponse(
        String docsPath,
        boolean restartRequired,
        String secretStorage,
        List<LlmProviderSetupOption> providers
    ) {
    }

    public record LlmTestRequest(
        boolean confirmed
    ) {
    }

    public record LlmTestResponse(
        String outcome,
        String message,
        String provider,
        String model,
        String promptVersion,
        Long latencyMs,
        Integer responseChars,
        String error
    ) {
    }

    public record EvidenceBundleManifestSummary(
        String schemaVersion,
        String generatedAt,
        String reportId,
        String incidentId,
        String clusterId,
        String nodeName,
        int evidenceCount,
        String hashAlgorithm,
        int entryCount,
        List<EvidenceBundleManifestEntry> entries,
        boolean signatureEnabled,
        String signatureAlgorithm,
        String signatureKeyId,
        String signatureCanonicalization,
        String signatureReason,
        String filename,
        long rawBytes,
        long zipBytes,
        long maxBundleBytes,
        String verificationCommand
    ) {
    }

    public record EvidenceBundleManifestEntry(
        String path,
        String sha256
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

    public record DemoScenarioRunRequest(
        boolean confirmed,
        String clusterId,
        @Size(max = 255) String nodeName
    ) {
        public String nodeNameOrDefault() {
            return nodeName == null || nodeName.isBlank() ? "demo-worker-01" : nodeName.trim();
        }
    }

    public record DemoScenario(
        String key,
        String name,
        String alertName,
        String description
    ) {
    }

    public record DemoScenarioRunResponse(
        DemoScenario scenario,
        ClusterView cluster,
        AnalysisTask analysisTask
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

    public record UserLoginIdChangeRequest(
        @NotBlank @Size(max = 256) String currentPassword,
        @NotBlank @Size(min = 3, max = 255)
        @Pattern(regexp = "^[A-Za-z0-9._@+-]+$")
        String newUsername
    ) {
        public String normalizedUsername() {
            return newUsername.trim().toLowerCase();
        }
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
        List<String> supportingEvidence,
        int confidenceScore,
        List<String> evidencePaths
    ) {
        public RootCauseCandidate(
            String cause,
            Confidence confidence,
            List<String> supportingEvidence
        ) {
            this(cause, confidence, supportingEvidence, defaultScore(confidence), List.of());
        }

        public RootCauseCandidate {
            confidenceScore = Math.max(0, Math.min(100, confidenceScore));
            supportingEvidence = supportingEvidence == null ? List.of() : List.copyOf(supportingEvidence);
            evidencePaths = evidencePaths == null ? List.of() : List.copyOf(evidencePaths);
        }

        private static int defaultScore(Confidence confidence) {
            if (confidence == null) {
                return 0;
            }
            return switch (confidence) {
                case high -> 80;
                case medium -> 55;
                case low -> 25;
            };
        }
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

    public record ActionManualCompletionRequest(
        boolean confirmed,
        @NotBlank @Size(max = 1000) String note
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
        String latestReportId,
        Instant resolvedAt,
        String resolutionSource,
        String resolutionNote,
        String recurrenceOfIncidentId,
        int recurrenceSequence,
        List<String> nodeNames
    ) {
        public Incident(
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
            String latestReportId,
            Instant resolvedAt,
            String resolutionSource,
            String resolutionNote,
            String recurrenceOfIncidentId,
            int recurrenceSequence
        ) {
            this(
                incidentId,
                clusterId,
                nodeName,
                alertName,
                rootCause,
                status,
                occurrenceCount,
                firstSeenAt,
                lastSeenAt,
                latestEvidenceId,
                latestReportId,
                resolvedAt,
                resolutionSource,
                resolutionNote,
                recurrenceOfIncidentId,
                recurrenceSequence,
                nodeName == null ? List.of() : List.of(nodeName)
            );
        }

        public Incident(
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
            this(
                incidentId,
                clusterId,
                nodeName,
                alertName,
                rootCause,
                status,
                occurrenceCount,
                firstSeenAt,
                lastSeenAt,
                latestEvidenceId,
                latestReportId,
                null,
                null,
                null,
                null,
                0,
                nodeName == null ? List.of() : List.of(nodeName)
            );
        }

        public Incident {
            nodeNames = nodeNames == null || nodeNames.isEmpty()
                ? nodeName == null ? List.of() : List.of(nodeName)
                : List.copyOf(nodeNames);
        }
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
        String signalFamily,
        String severity,
        String title,
        String detail,
        String evidenceId,
        boolean rootTrigger,
        String evidenceType,
        List<String> evidencePaths,
        boolean rootCauseCandidate,
        Integer rootCauseScore,
        Map<String, Object> evidenceQuality
    ) {
        public TimelineNode(
            String id,
            Instant timestamp,
            String component,
            String eventType,
            String signalFamily,
            String severity,
            String title,
            String detail,
            String evidenceId,
            boolean rootTrigger
        ) {
            this(
                id,
                timestamp,
                component,
                eventType,
                signalFamily,
                severity,
                title,
                detail,
                evidenceId,
                rootTrigger,
                "derived_signal",
                List.of(),
                false,
                null,
                Map.of()
            );
        }

        public TimelineNode {
            evidenceType = evidenceType == null || evidenceType.isBlank() ? "derived_signal" : evidenceType;
            evidencePaths = evidencePaths == null ? List.of() : List.copyOf(evidencePaths);
            evidenceQuality = evidenceQuality == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(evidenceQuality));
        }
    }

    public record TimelineEdge(
        String source,
        String target,
        String relationship,
        String ruleId,
        double confidence,
        boolean inferred,
        String evidenceBasis,
        String direction,
        String strength
    ) {
        public TimelineEdge(
            String source,
            String target,
            String relationship,
            String ruleId,
            double confidence,
            boolean inferred
        ) {
            this(
                source,
                target,
                relationship,
                ruleId,
                confidence,
                inferred,
                inferred ? "rule_based_causal_relation" : "observed_temporal_sequence",
                "upstream_to_downstream",
                confidence >= 0.9 ? "strong" : confidence >= 0.7 ? "moderate" : "weak"
            );
        }

        public TimelineEdge {
            evidenceBasis = evidenceBasis == null || evidenceBasis.isBlank()
                ? inferred ? "rule_based_causal_relation" : "observed_temporal_sequence"
                : evidenceBasis;
            direction = direction == null || direction.isBlank() ? "upstream_to_downstream" : direction;
            strength = strength == null || strength.isBlank()
                ? confidence >= 0.9 ? "strong" : confidence >= 0.7 ? "moderate" : "weak"
                : strength;
        }
    }

    public record IncidentTimeline(
        String incidentId,
        Instant from,
        Instant to,
        List<TimelineNode> nodes,
        List<TimelineEdge> edges,
        Map<String, Object> summary
    ) {
        public IncidentTimeline(
            String incidentId,
            Instant from,
            Instant to,
            List<TimelineNode> nodes,
            List<TimelineEdge> edges
        ) {
            this(incidentId, from, to, nodes, edges, Map.of());
        }

        public IncidentTimeline {
            nodes = nodes == null ? List.of() : List.copyOf(nodes);
            edges = edges == null ? List.of() : List.copyOf(edges);
            summary = summary == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(summary));
        }
    }

    public record TopologyEntity(
        String id,
        String kind,
        String namespace,
        String name,
        String nodeName,
        List<String> roles,
        Map<String, String> labels,
        Map<String, Object> attributes
    ) {
        public TopologyEntity {
            roles = roles == null ? List.of() : List.copyOf(roles);
            labels = labels == null ? Map.of() : Map.copyOf(labels);
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        }
    }

    public record TopologyRelation(
        String source,
        String target,
        String relationship,
        double confidence,
        String evidencePath
    ) {
    }

    public record TopologyObservation(
        String observationId,
        String clusterId,
        String sourceEvidenceId,
        String sourceNodeName,
        Instant observedAt,
        List<TopologyEntity> entities,
        List<TopologyRelation> relations,
        boolean nodeInventoryCollected,
        boolean podInventoryCollected,
        boolean inventoryCollected,
        boolean inventoryComplete
    ) {
        public TopologyObservation {
            entities = entities == null ? List.of() : List.copyOf(entities);
            relations = relations == null ? List.of() : List.copyOf(relations);
        }
    }

    public record ClusterTopology(
        String clusterId,
        Instant observedAt,
        List<TopologyEntity> entities,
        List<TopologyRelation> relations,
        List<String> nodes,
        List<String> services,
        boolean inventoryComplete
    ) {
        public ClusterTopology {
            entities = entities == null ? List.of() : List.copyOf(entities);
            relations = relations == null ? List.of() : List.copyOf(relations);
            nodes = nodes == null ? List.of() : List.copyOf(nodes);
            services = services == null ? List.of() : List.copyOf(services);
        }
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
