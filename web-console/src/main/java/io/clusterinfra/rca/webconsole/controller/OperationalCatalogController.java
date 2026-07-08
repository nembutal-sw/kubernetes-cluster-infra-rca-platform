package io.clusterinfra.rca.webconsole.controller;

import io.clusterinfra.rca.webconsole.catalog.OperationalCatalogService;
import io.clusterinfra.rca.webconsole.domain.RcaModels.CatalogOverrideDraft;
import io.clusterinfra.rca.webconsole.domain.RcaModels.CatalogOverrideDraftCreateRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.CatalogOverrideDraftDecisionRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.CatalogOverrideHandoff;
import io.clusterinfra.rca.webconsole.domain.RcaModels.CatalogOverridePreviewRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.UserAccount;
import io.clusterinfra.rca.webconsole.security.AccessService;
import io.clusterinfra.rca.webconsole.service.AuditService;
import io.clusterinfra.rca.webconsole.service.CatalogOverrideWorkflowService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OperationalCatalogController {
    private final OperationalCatalogService catalogService;
    private final CatalogOverrideWorkflowService overrideWorkflow;
    private final AccessService access;
    private final AuditService audit;

    public OperationalCatalogController(
        OperationalCatalogService catalogService,
        CatalogOverrideWorkflowService overrideWorkflow,
        AccessService access,
        AuditService audit
    ) {
        this.catalogService = catalogService;
        this.overrideWorkflow = overrideWorkflow;
        this.access = access;
        this.audit = audit;
    }

    @GetMapping({"/api/catalog", "/api/v1/catalog"})
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','VIEWER','APPROVER','AUDITOR')")
    public Map<String, Object> catalog() {
        return catalogService.detail();
    }

    @PostMapping({"/api/catalog/preview", "/api/v1/catalog/preview"})
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public Map<String, Object> preview(
        @Valid @RequestBody CatalogOverridePreviewRequest request,
        Authentication authentication,
        HttpServletRequest servletRequest
    ) {
        UserAccount user = access.currentUser(authentication);
        Map<String, Object> result = catalogService.previewOverride(request.overrideJson());
        boolean valid = Boolean.TRUE.equals(result.get("valid"));
        audit.user(
            user,
            "catalog.override.preview",
            "catalog",
            "operational-catalog",
            valid ? "success" : "rejected",
            Map.of(
                "valid", valid,
                "diff_count", diffCount(result),
                "diff_truncated", Boolean.TRUE.equals(result.get("diff_truncated")),
                "reason_provided", request.reason() != null && !request.reason().isBlank(),
                "message", String.valueOf(result.getOrDefault("message", ""))
            ),
            servletRequest
        );
        return result;
    }

    @GetMapping({"/api/catalog/overrides/drafts", "/api/v1/catalog/overrides/drafts"})
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','APPROVER','AUDITOR')")
    public List<CatalogOverrideDraft> drafts(
        @RequestParam(required = false) String status,
        @RequestParam(required = false) Integer limit
    ) {
        return overrideWorkflow.list(status, limit);
    }

    @PostMapping({"/api/catalog/overrides/drafts", "/api/v1/catalog/overrides/drafts"})
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public CatalogOverrideDraft createDraft(
        @Valid @RequestBody CatalogOverrideDraftCreateRequest request,
        Authentication authentication,
        HttpServletRequest servletRequest
    ) {
        return overrideWorkflow.create(request, access.currentUser(authentication), servletRequest);
    }

    @GetMapping({"/api/catalog/overrides/drafts/{draftId}", "/api/v1/catalog/overrides/drafts/{draftId}"})
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','APPROVER','AUDITOR')")
    public CatalogOverrideDraft draft(@PathVariable String draftId) {
        return overrideWorkflow.get(draftId);
    }

    @PostMapping({"/api/catalog/overrides/drafts/{draftId}/approve", "/api/v1/catalog/overrides/drafts/{draftId}/approve"})
    @PreAuthorize("hasAnyRole('ADMIN','APPROVER')")
    public CatalogOverrideDraft approveDraft(
        @PathVariable String draftId,
        @Valid @RequestBody CatalogOverrideDraftDecisionRequest request,
        Authentication authentication,
        HttpServletRequest servletRequest
    ) {
        return overrideWorkflow.approve(draftId, request, access.currentUser(authentication), servletRequest);
    }

    @PostMapping({"/api/catalog/overrides/drafts/{draftId}/reject", "/api/v1/catalog/overrides/drafts/{draftId}/reject"})
    @PreAuthorize("hasAnyRole('ADMIN','APPROVER')")
    public CatalogOverrideDraft rejectDraft(
        @PathVariable String draftId,
        @Valid @RequestBody CatalogOverrideDraftDecisionRequest request,
        Authentication authentication,
        HttpServletRequest servletRequest
    ) {
        return overrideWorkflow.reject(draftId, request, access.currentUser(authentication), servletRequest);
    }

    @PostMapping({"/api/catalog/overrides/drafts/{draftId}/discard", "/api/v1/catalog/overrides/drafts/{draftId}/discard"})
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public CatalogOverrideDraft discardDraft(
        @PathVariable String draftId,
        @Valid @RequestBody CatalogOverrideDraftDecisionRequest request,
        Authentication authentication,
        HttpServletRequest servletRequest
    ) {
        return overrideWorkflow.discard(draftId, request, access.currentUser(authentication), servletRequest);
    }

    @GetMapping({"/api/catalog/overrides/drafts/{draftId}/handoff", "/api/v1/catalog/overrides/drafts/{draftId}/handoff"})
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','APPROVER')")
    public CatalogOverrideHandoff handoff(@PathVariable String draftId) {
        return overrideWorkflow.handoff(draftId);
    }

    private int diffCount(Map<String, Object> result) {
        Object diffCount = result.get("diff_count");
        if (diffCount instanceof Number number) {
            return number.intValue();
        }
        Object diff = result.get("diff");
        return diff instanceof List<?> values ? values.size() : 0;
    }
}
