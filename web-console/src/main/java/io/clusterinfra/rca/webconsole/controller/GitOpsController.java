package io.clusterinfra.rca.webconsole.controller;

import io.clusterinfra.rca.webconsole.domain.RcaModels.GitOpsChange;
import io.clusterinfra.rca.webconsole.domain.RcaModels.GitOpsChangeCreateRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.GitOpsOutcomeUpdateRequest;
import io.clusterinfra.rca.webconsole.security.AccessService;
import io.clusterinfra.rca.webconsole.service.GitOpsChangeService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class GitOpsController {
    private final GitOpsChangeService service;
    private final AccessService access;

    public GitOpsController(GitOpsChangeService service, AccessService access) {
        this.service = service;
        this.access = access;
    }

    @PostMapping({"/api/catalog/overrides/drafts/{draftId}/gitops-changes", "/api/v1/catalog/overrides/drafts/{draftId}/gitops-changes"})
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public GitOpsChange create(
        @PathVariable String draftId,
        @Valid @RequestBody GitOpsChangeCreateRequest request,
        Authentication authentication,
        HttpServletRequest servletRequest
    ) {
        return service.createForCatalogDraft(
            draftId, request, access.currentUser(authentication), servletRequest
        );
    }

    @GetMapping({"/api/gitops/changes", "/api/v1/gitops/changes"})
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','VIEWER','APPROVER','AUDITOR')")
    public List<GitOpsChange> list(
        @RequestParam(required = false) String sourceType,
        @RequestParam(required = false) String sourceId,
        @RequestParam(required = false) Integer limit
    ) {
        return service.list(sourceType, sourceId, limit);
    }

    @GetMapping({"/api/gitops/changes/{changeId}", "/api/v1/gitops/changes/{changeId}"})
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','VIEWER','APPROVER','AUDITOR')")
    public GitOpsChange get(@PathVariable String changeId) {
        return service.get(changeId);
    }

    @PostMapping({"/api/gitops/changes/{changeId}/outcome", "/api/v1/gitops/changes/{changeId}/outcome"})
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public GitOpsChange updateOutcome(
        @PathVariable String changeId,
        @Valid @RequestBody GitOpsOutcomeUpdateRequest request,
        Authentication authentication,
        HttpServletRequest servletRequest
    ) {
        return service.updateOutcome(
            changeId, request, access.currentUser(authentication), servletRequest
        );
    }
}
