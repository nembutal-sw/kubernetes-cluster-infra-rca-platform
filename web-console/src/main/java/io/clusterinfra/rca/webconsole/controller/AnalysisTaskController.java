package io.clusterinfra.rca.webconsole.controller;

import io.clusterinfra.rca.webconsole.domain.RcaModels.ActionDecisionRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.AnalysisTask;
import io.clusterinfra.rca.webconsole.domain.RcaModels.AnalysisTaskStatus;
import io.clusterinfra.rca.webconsole.domain.RcaModels.UserAccount;
import io.clusterinfra.rca.webconsole.persistence.AnalysisTaskRepository;
import io.clusterinfra.rca.webconsole.security.AccessService;
import io.clusterinfra.rca.webconsole.service.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@RestController
public class AnalysisTaskController {
    private final AnalysisTaskRepository analysisTasks;
    private final AccessService access;
    private final AuditService audit;

    public AnalysisTaskController(
        AnalysisTaskRepository analysisTasks,
        AccessService access,
        AuditService audit
    ) {
        this.analysisTasks = analysisTasks;
        this.access = access;
        this.audit = audit;
    }

    @GetMapping("/api/rca/analysis-tasks")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','VIEWER')")
    public List<AnalysisTask> analysisTasks(
        @RequestParam(name = "status", required = false) AnalysisTaskStatus status,
        @RequestParam(name = "limit", defaultValue = "200") Integer limit
    ) {
        return analysisTasks.list(status, limit);
    }

    @GetMapping("/api/rca/analysis-tasks/{taskId}")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','VIEWER')")
    public AnalysisTask analysisTask(@PathVariable String taskId) {
        return analysisTasks.find(taskId)
            .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "analysis task not found"));
    }

    @PostMapping("/api/rca/analysis-tasks/{taskId}/retry")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public AnalysisTask retryAnalysisTask(
        @PathVariable String taskId,
        @Valid @RequestBody ActionDecisionRequest request,
        Authentication authentication,
        HttpServletRequest servletRequest
    ) {
        if (!request.confirmed()) {
            throw new ResponseStatusException(BAD_REQUEST, "analysis retry confirmation is required");
        }
        UserAccount user = access.currentUser(authentication);
        AnalysisTask task = analysisTasks.retry(taskId)
            .orElseThrow(() -> new ResponseStatusException(
                CONFLICT,
                "only dead-letter analysis tasks can be retried"
            ));
        audit.user(
            user,
            "analysis.task_requeued",
            "analysis_task",
            taskId,
            "queued",
            Map.of("note", request.note() == null ? "" : request.note()),
            servletRequest
        );
        return task;
    }
}
