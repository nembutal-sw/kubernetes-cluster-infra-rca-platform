package io.clusterinfra.rca.webconsole.service;

import io.clusterinfra.rca.webconsole.catalog.OperationalCatalogService;
import io.clusterinfra.rca.webconsole.domain.RcaModels.CatalogOverrideDraft;
import io.clusterinfra.rca.webconsole.domain.RcaModels.CatalogOverrideDraftCreateRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.CatalogOverrideDraftDecisionRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.CatalogOverrideHandoff;
import io.clusterinfra.rca.webconsole.domain.RcaModels.CatalogOverrideStatus;
import io.clusterinfra.rca.webconsole.domain.RcaModels.UserAccount;
import io.clusterinfra.rca.webconsole.persistence.CatalogOverrideDraftRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
public class CatalogOverrideWorkflowService {
    private final OperationalCatalogService catalog;
    private final CatalogOverrideDraftRepository drafts;
    private final AuditService audit;

    public CatalogOverrideWorkflowService(
        OperationalCatalogService catalog,
        CatalogOverrideDraftRepository drafts,
        AuditService audit
    ) {
        this.catalog = catalog;
        this.drafts = drafts;
        this.audit = audit;
    }

    public List<CatalogOverrideDraft> list(String status, Integer limit) {
        return drafts.list(normalizedStatus(status), limit == null ? 50 : limit);
    }

    public CatalogOverrideDraft get(String draftId) {
        return drafts.find(draftId)
            .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "catalog override draft not found"));
    }

    public CatalogOverrideDraft create(
        CatalogOverrideDraftCreateRequest request,
        UserAccount user,
        HttpServletRequest servletRequest
    ) {
        Map<String, Object> preview = catalog.previewOverride(request.overrideJson());
        boolean valid = Boolean.TRUE.equals(preview.get("valid"));
        if (!valid) {
            audit.user(
                user,
                "catalog.override_draft.create",
                "catalog_override_draft",
                "new",
                "rejected",
                Map.of(
                    "message", String.valueOf(preview.getOrDefault("message", "invalid override")),
                    "reason_provided", request.reason() != null && !request.reason().isBlank()
                ),
                servletRequest
            );
            throw new ResponseStatusException(BAD_REQUEST, String.valueOf(preview.getOrDefault("message", "invalid override")));
        }
        CatalogOverrideDraft draft = drafts.create(
            request.overrideJson(),
            mapValue(preview.get("summary")),
            listValue(preview.get("diff")),
            Boolean.TRUE.equals(preview.get("diff_truncated")),
            String.valueOf(preview.getOrDefault("message", "Override is valid.")),
            request.reason(),
            user.email()
        );
        auditDraft(user, draft, "created", servletRequest);
        return draft;
    }

    public CatalogOverrideDraft approve(
        String draftId,
        CatalogOverrideDraftDecisionRequest request,
        UserAccount user,
        HttpServletRequest servletRequest
    ) {
        CatalogOverrideDraft draft = decide(draftId, request, user, CatalogOverrideStatus.approved);
        auditDraft(user, draft, "approved", servletRequest);
        return draft;
    }

    public CatalogOverrideDraft reject(
        String draftId,
        CatalogOverrideDraftDecisionRequest request,
        UserAccount user,
        HttpServletRequest servletRequest
    ) {
        CatalogOverrideDraft draft = decide(draftId, request, user, CatalogOverrideStatus.rejected);
        auditDraft(user, draft, "rejected", servletRequest);
        return draft;
    }

    public CatalogOverrideDraft discard(
        String draftId,
        CatalogOverrideDraftDecisionRequest request,
        UserAccount user,
        HttpServletRequest servletRequest
    ) {
        CatalogOverrideDraft draft = decide(draftId, request, user, CatalogOverrideStatus.discarded);
        auditDraft(user, draft, "discarded", servletRequest);
        return draft;
    }

    public CatalogOverrideHandoff handoff(String draftId) {
        CatalogOverrideDraft draft = get(draftId);
        if (draft.status() != CatalogOverrideStatus.approved) {
            throw new ResponseStatusException(CONFLICT, "catalog override draft must be approved before handoff");
        }
        List<String> changedPaths = draft.diff().stream()
            .map(change -> String.valueOf(change.getOrDefault("path", "/")))
            .limit(20)
            .toList();
        String title = "Update RCA operational catalog override " + draft.draftId();
        String body = """
            ## Summary

            Apply an approved Cluster RCA operational catalog override.

            ## Safety

            - This change was validated by the Web Console catalog preview.
            - `plan.executable=true` is rejected by the platform and must remain disabled.
            - The platform does not apply this draft automatically.

            ## Changed Paths

            %s

            ## Rollout

            1. Commit the override JSON as `ops/catalog/operational-catalog.override.json`.
            2. Mount the file into the backend container or pod.
            3. Set `RCA_CATALOG_EXTERNAL_PATH` to the mounted file path.
            4. Roll out one canary instance and verify `/api/v1/catalog`.
            5. Roll out the remaining instances after the checksum is confirmed.
            """.formatted(markdownList(changedPaths));
        return new CatalogOverrideHandoff(
            draft.draftId(),
            draft.status(),
            "Use GitOps PR or a controlled runbook. The console will not mutate the running catalog.",
            List.of(
                "Save the approved override JSON as ops/catalog/operational-catalog.override.json.",
                "Create a GitOps PR that mounts the file and sets RCA_CATALOG_EXTERNAL_PATH.",
                "Run the catalog preview API in the target environment before rollout.",
                "Roll out a canary backend instance and verify /api/v1/catalog checksum and rule state.",
                "Record the deployment outcome in the change ticket or runbook."
            ),
            Map.of("ops/catalog/operational-catalog.override.json", draft.overrideJson()),
            title,
            body
        );
    }

    private CatalogOverrideDraft decide(
        String draftId,
        CatalogOverrideDraftDecisionRequest request,
        UserAccount user,
        CatalogOverrideStatus status
    ) {
        if (!request.confirmed()) {
            throw new ResponseStatusException(BAD_REQUEST, "catalog override decision confirmation is required");
        }
        CatalogOverrideDraft existing = get(draftId);
        if (existing.status() != CatalogOverrideStatus.draft) {
            throw new ResponseStatusException(CONFLICT, "catalog override draft is not pending decision");
        }
        return drafts.decide(draftId, status, user.email(), request.note())
            .orElseThrow(() -> new ResponseStatusException(CONFLICT, "catalog override draft was already decided"));
    }

    private void auditDraft(
        UserAccount user,
        CatalogOverrideDraft draft,
        String outcome,
        HttpServletRequest servletRequest
    ) {
        audit.user(
            user,
            "catalog.override_draft",
            "catalog_override_draft",
            draft.draftId(),
            outcome,
            Map.of(
                "status", draft.status().name(),
                "diff_count", draft.diff().size(),
                "diff_truncated", draft.diffTruncated(),
                "reason_provided", draft.reason() != null && !draft.reason().isBlank()
            ),
            servletRequest
        );
    }

    private String normalizedStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return CatalogOverrideStatus.valueOf(status.trim().toLowerCase(java.util.Locale.ROOT)).name();
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(BAD_REQUEST, "unknown catalog override draft status");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listValue(Object value) {
        return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    private String markdownList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "- No catalog changes detected.";
        }
        return values.stream().map(value -> "- `" + value + "`").reduce((left, right) -> left + "\n" + right).orElse("");
    }
}
