package io.clusterinfra.rca.webconsole.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.clusterinfra.rca.webconsole.config.RcaConsoleProperties;
import io.clusterinfra.rca.webconsole.domain.RcaModels.CatalogOverrideDraft;
import io.clusterinfra.rca.webconsole.domain.RcaModels.CatalogOverrideHandoff;
import io.clusterinfra.rca.webconsole.domain.RcaModels.CatalogOverrideStatus;
import io.clusterinfra.rca.webconsole.domain.RcaModels.GitOpsChange;
import io.clusterinfra.rca.webconsole.domain.RcaModels.GitOpsChangeCreateRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.GitOpsChangeState;
import io.clusterinfra.rca.webconsole.domain.RcaModels.GitOpsDeploymentState;
import io.clusterinfra.rca.webconsole.domain.RcaModels.UserAccount;
import io.clusterinfra.rca.webconsole.domain.RcaModels.UserRole;
import io.clusterinfra.rca.webconsole.domain.RcaModels.UserStatus;
import io.clusterinfra.rca.webconsole.gitops.GitOpsProvider;
import io.clusterinfra.rca.webconsole.persistence.GitOpsChangeRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class GitOpsChangeServiceTests {
    private CatalogOverrideWorkflowService catalogWorkflow;
    private GitOpsChangeRepository changes;
    private GitOpsProvider provider;
    private GitOpsChangeService service;
    private UserAccount user;

    @BeforeEach
    void setUp() {
        catalogWorkflow = mock(CatalogOverrideWorkflowService.class);
        changes = mock(GitOpsChangeRepository.class);
        provider = mock(GitOpsProvider.class);
        when(provider.id()).thenReturn("github");
        RcaConsoleProperties properties = new RcaConsoleProperties();
        properties.getGitOps().setEnabled(true);
        properties.getGitOps().setRepository("acme/rca-config");
        properties.getGitOps().setToken("token-value");
        properties.getGitOps().setWebhookSecret("webhook-secret");
        service = new GitOpsChangeService(
            catalogWorkflow, changes, List.of(provider), properties, mock(AuditService.class)
        );
        user = new UserAccount(
            "user-1", "operator@example.com", "Operator", UserRole.operator, UserRole.operator,
            UserStatus.active, null, null, null, Instant.now(), Instant.now()
        );
    }

    @Test
    void approvedDraftCreatesOneTrackedPullRequest() {
        GitOpsChange pending = change(GitOpsChangeState.creating, null);
        GitOpsChange opened = change(GitOpsChangeState.open, 42L);
        when(catalogWorkflow.get("draft-1")).thenReturn(draft(CatalogOverrideStatus.approved));
        when(catalogWorkflow.handoff("draft-1")).thenReturn(handoff());
        when(changes.createPending(any(), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(new GitOpsChangeRepository.PendingClaim(pending, true));
        when(provider.createPullRequest(any(), any(), any(), any()))
            .thenReturn(new GitOpsProvider.PullRequestResult(42, "https://github.test/pr/42", "open", "sha"));
        when(changes.markOpened(any(), anyLong(), any(), any(), any())).thenReturn(opened);

        GitOpsChange result = service.createForCatalogDraft(
            "draft-1", new GitOpsChangeCreateRequest(true), user, mock(HttpServletRequest.class)
        );

        assertThat(result.pullRequestState()).isEqualTo(GitOpsChangeState.open);
        assertThat(result.pullRequestNumber()).isEqualTo(42);
        verify(provider).createPullRequest(pending, "{}", "Catalog update", "Reviewed body");
    }

    @Test
    void duplicateClaimReturnsExistingChangeWithoutAnotherProviderCall() {
        GitOpsChange existing = change(GitOpsChangeState.open, 42L);
        when(catalogWorkflow.get("draft-1")).thenReturn(draft(CatalogOverrideStatus.approved));
        when(catalogWorkflow.handoff("draft-1")).thenReturn(handoff());
        when(changes.createPending(any(), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(new GitOpsChangeRepository.PendingClaim(existing, false));

        GitOpsChange result = service.createForCatalogDraft(
            "draft-1", new GitOpsChangeCreateRequest(true), user, mock(HttpServletRequest.class)
        );

        assertThat(result).isEqualTo(existing);
        verify(provider, never()).createPullRequest(any(), any(), any(), any());
    }

    @Test
    void unapprovedDraftCannotCreatePullRequest() {
        when(catalogWorkflow.get("draft-1")).thenReturn(draft(CatalogOverrideStatus.draft));

        assertThatThrownBy(() -> service.createForCatalogDraft(
            "draft-1", new GitOpsChangeCreateRequest(true), user, mock(HttpServletRequest.class)
        )).isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("must be approved");
        verify(provider, never()).createPullRequest(any(), any(), any(), any());
    }

    private CatalogOverrideDraft draft(CatalogOverrideStatus status) {
        Instant now = Instant.parse("2026-07-13T00:00:00Z");
        return new CatalogOverrideDraft(
            "draft-1", status, "{}", Map.of(), List.of(), false, "valid", "reason",
            "operator@example.com", "approver@example.com", "approved", now, now, now
        );
    }

    private CatalogOverrideHandoff handoff() {
        return new CatalogOverrideHandoff(
            "draft-1", CatalogOverrideStatus.approved, "GitOps", List.of(), Map.of(),
            "Catalog update", "Reviewed body"
        );
    }

    private GitOpsChange change(GitOpsChangeState state, Long pullRequestNumber) {
        Instant now = Instant.parse("2026-07-13T00:00:00Z");
        return new GitOpsChange(
            "gitops-1", "catalog_override_draft", "draft-1", "github", "acme/rca-config",
            "rca/catalog-draft-1", "main", "ops/catalog/operational-catalog.override.json",
            pullRequestNumber, pullRequestNumber == null ? null : "https://github.test/pr/42", state,
            pullRequestNumber == null ? null : "sha", GitOpsDeploymentState.pending, null, null, null,
            "operator@example.com", now, now, null, null
        );
    }
}
