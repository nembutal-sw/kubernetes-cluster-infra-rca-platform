package io.clusterinfra.rca.webconsole.controller;

import io.clusterinfra.rca.webconsole.domain.RcaModels.OverviewSummary;
import io.clusterinfra.rca.webconsole.service.OverviewSummaryService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OverviewController {
    private final OverviewSummaryService overview;

    public OverviewController(OverviewSummaryService overview) {
        this.overview = overview;
    }

    @GetMapping("/api/v1/overview/summary")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','VIEWER','AUDITOR','APPROVER')")
    public OverviewSummary summary() {
        return overview.summary();
    }
}
