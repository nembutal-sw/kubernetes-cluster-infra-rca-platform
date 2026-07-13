package io.clusterinfra.rca.webconsole.controller;

import io.clusterinfra.rca.webconsole.analysis.CollectorEvidenceAdapter;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/evidence")
public class EvidenceSchemaController {
    private final CollectorEvidenceAdapter adapter;

    public EvidenceSchemaController(CollectorEvidenceAdapter adapter) {
        this.adapter = adapter;
    }

    @GetMapping("/schemas")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','VIEWER','APPROVER','AUDITOR')")
    public Map<String, Object> schemas() {
        return adapter.schemas();
    }
}
