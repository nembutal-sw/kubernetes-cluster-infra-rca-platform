package io.clusterinfra.rca.webconsole.service;

import static org.springframework.http.HttpStatus.BAD_GATEWAY;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;

import io.clusterinfra.rca.webconsole.config.RcaConsoleProperties;
import io.clusterinfra.rca.webconsole.domain.RcaModels.CatalogOverrideDraft;
import io.clusterinfra.rca.webconsole.domain.RcaModels.CatalogOverrideHandoff;
import io.clusterinfra.rca.webconsole.domain.RcaModels.CatalogOverrideStatus;
import io.clusterinfra.rca.webconsole.domain.RcaModels.GitOpsChange;
import io.clusterinfra.rca.webconsole.domain.RcaModels.GitOpsChangeCreateRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.GitOpsChangeState;
import io.clusterinfra.rca.webconsole.domain.RcaModels.GitOpsDeploymentState;
import io.clusterinfra.rca.webconsole.domain.RcaModels.GitOpsOutcomeUpdateRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.UserAccount;
import io.clusterinfra.rca.webconsole.gitops.GitOpsProvider;
import io.clusterinfra.rca.webconsole.persistence.GitOpsChangeRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class GitOpsChangeService {
    private static final String CATALOG_SOURCE = "catalog_override_draft";

    private final CatalogOverrideWorkflowService catalogWorkflow;
    private final GitOpsChangeRepository changes;
    private final Map<String, GitOpsProvider> providers;
    private final RcaConsoleProperties properties;
    private final AuditService audit;

    public GitOpsChangeService(
        CatalogOverrideWorkflowService catalogWorkflow,
        GitOpsChangeRepository changes,
        List<GitOpsProvider> providers,
        RcaConsoleProperties properties,
        AuditService audit
    ) {
        this.catalogWorkflow = catalogWorkflow;
        this.changes = changes;
        this.providers = providers.stream().collect(Collectors.toUnmodifiableMap(
            provider -> provider.id().toLowerCase(Locale.ROOT),
            Function.identity()
        ));
        this.properties = properties;
        this.audit = audit;
    }

    public GitOpsChange createForCatalogDraft(
        String draftId,
        GitOpsChangeCreateRequest request,
        UserAccount user,
        HttpServletRequest servletRequest
    ) {
        if (request == null || !request.confirmed()) {
            throw new ResponseStatusException(BAD_REQUEST, "GitOps PR creation confirmation is required");
        }
        RcaConsoleProperties.GitOps config = properties.getGitOps();
        validateConfiguration(config);
        CatalogOverrideDraft draft = catalogWorkflow.get(draftId);
        if (draft.status() != CatalogOverrideStatus.approved) {
            throw new ResponseStatusException(CONFLICT, "catalog override draft must be approved before PR creation");
        }
        CatalogOverrideHandoff handoff = catalogWorkflow.handoff(draftId);
        String providerId = config.getProvider().toLowerCase(Locale.ROOT);
        String branch = branchName(draftId);
        GitOpsChangeRepository.PendingClaim claim = changes.createPending(
            CATALOG_SOURCE,
            draftId,
            providerId,
            config.getRepository(),
            branch,
            config.getBaseBranch(),
            config.getFilePath(),
            user.email()
        );
        if (!claim.claimed()) {
            audit.user(
                user, "gitops.change.create", "gitops_change", claim.change().changeId(), "deduplicated",
                auditDetails(claim.change()), servletRequest
            );
            return claim.change();
        }

        GitOpsChange pending = claim.change();
        try {
            GitOpsProvider.PullRequestResult result = provider(providerId).createPullRequest(
                pending,
                draft.overrideJson(),
                handoff.pullRequestTitle(),
                handoff.pullRequestBody()
            );
            GitOpsChange opened = changes.markOpened(
                pending.changeId(), result.number(), result.url(), result.headSha(), result.state()
            );
            audit.user(
                user, "gitops.change.create", "gitops_change", opened.changeId(), "created",
                auditDetails(opened), servletRequest
            );
            return opened;
        } catch (RuntimeException exception) {
            GitOpsChange failed = changes.markFailed(pending.changeId(), exception.getMessage());
            audit.user(
                user, "gitops.change.create", "gitops_change", failed.changeId(), "failed",
                Map.of(
                    "provider", failed.provider(),
                    "repository", failed.repository(),
                    "source_id", failed.sourceId(),
                    "error_type", exception.getClass().getSimpleName()
                ),
                servletRequest
            );
            throw new ResponseStatusException(BAD_GATEWAY, "GitOps provider could not create the pull request");
        }
    }

    public List<GitOpsChange> list(String sourceType, String sourceId, Integer limit) {
        return changes.list(clean(sourceType), clean(sourceId), limit == null ? 50 : limit);
    }

    public GitOpsChange get(String changeId) {
        return changes.find(changeId)
            .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "GitOps change not found"));
    }

    public GitOpsChange updateOutcome(
        String changeId,
        GitOpsOutcomeUpdateRequest request,
        UserAccount user,
        HttpServletRequest servletRequest
    ) {
        if (request == null || !request.confirmed() || request.deploymentState() == null) {
            throw new ResponseStatusException(BAD_REQUEST, "GitOps outcome confirmation and deployment state are required");
        }
        GitOpsChange existing = get(changeId);
        if (existing.pullRequestState() != GitOpsChangeState.merged) {
            throw new ResponseStatusException(CONFLICT, "deployment outcome can only be recorded after PR merge");
        }
        validateTransition(existing.deploymentState(), request.deploymentState());
        GitOpsChange updated = changes.updateOutcome(
            changeId,
            request.deploymentState(),
            request.verificationResult(),
            request.rollbackReference()
        ).orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "GitOps change not found"));
        audit.user(
            user, "gitops.change.outcome", "gitops_change", updated.changeId(), updated.deploymentState().name(),
            Map.of(
                "pull_request_state", updated.pullRequestState().name(),
                "deployment_state", updated.deploymentState().name(),
                "verification_recorded", updated.verificationResult() != null,
                "rollback_recorded", updated.rollbackReference() != null
            ),
            servletRequest
        );
        return updated;
    }

    private GitOpsProvider provider(String providerId) {
        GitOpsProvider provider = providers.get(providerId);
        if (provider == null) {
            throw new ResponseStatusException(SERVICE_UNAVAILABLE, "configured GitOps provider is unavailable");
        }
        return provider;
    }

    private void validateConfiguration(RcaConsoleProperties.GitOps config) {
        if (!config.isEnabled()) {
            throw new ResponseStatusException(SERVICE_UNAVAILABLE, "GitOps integration is disabled");
        }
        if (!config.getRepository().matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) {
            throw new ResponseStatusException(SERVICE_UNAVAILABLE, "GitOps repository must use owner/repository format");
        }
        if (!config.getBaseBranch().matches("[A-Za-z0-9._/-]+") || config.getBaseBranch().contains("..")) {
            throw new ResponseStatusException(SERVICE_UNAVAILABLE, "GitOps base branch is invalid");
        }
        String filePath = config.getFilePath();
        if (filePath.startsWith("/") || filePath.contains("..") || !filePath.endsWith(".json")) {
            throw new ResponseStatusException(SERVICE_UNAVAILABLE, "GitOps catalog file path is invalid");
        }
    }

    private void validateTransition(GitOpsDeploymentState current, GitOpsDeploymentState requested) {
        boolean allowed = switch (current) {
            case pending -> requested == GitOpsDeploymentState.in_progress;
            case in_progress -> requested == GitOpsDeploymentState.succeeded
                || requested == GitOpsDeploymentState.failed;
            case succeeded, failed -> requested == GitOpsDeploymentState.rolled_back;
            case rolled_back -> false;
        };
        if (!allowed) {
            throw new ResponseStatusException(
                CONFLICT,
                "invalid GitOps deployment transition: " + current + " -> " + requested
            );
        }
    }

    private Map<String, Object> auditDetails(GitOpsChange change) {
        return Map.of(
            "provider", change.provider(),
            "repository", change.repository(),
            "source_type", change.sourceType(),
            "source_id", change.sourceId(),
            "pull_request_state", change.pullRequestState().name()
        );
    }

    private String branchName(String draftId) {
        String safe = draftId.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "-");
        return "rca/catalog-" + safe;
    }

    private String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
