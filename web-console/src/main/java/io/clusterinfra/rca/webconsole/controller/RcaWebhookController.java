package io.clusterinfra.rca.webconsole.controller;

import io.clusterinfra.rca.webconsole.domain.RcaModels.AlertmanagerPayload;
import io.clusterinfra.rca.webconsole.domain.RcaModels.WebhookIngestResponse;
import io.clusterinfra.rca.webconsole.service.AlertIngestService;
import io.clusterinfra.rca.webconsole.service.AuditService;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RcaWebhookController {
    private final AlertIngestService ingest;
    private final AuditService audit;

    public RcaWebhookController(AlertIngestService ingest, AuditService audit) {
        this.ingest = ingest;
        this.audit = audit;
    }

    @PostMapping("/api/webhooks/alertmanager")
    public WebhookIngestResponse alertmanager(@Valid @RequestBody AlertmanagerPayload payload) {
        WebhookIngestResponse response = ingest.ingestAlertmanager(payload);
        audit.system(
            "alertmanager",
            "webhook.ingest",
            "webhook",
            "alertmanager",
            "success",
            Map.of(
                "received_alerts", response.receivedAlerts(),
                "created_reports", response.createdReports().size(),
                "queued_analysis_tasks", response.queuedAnalysisTasks().size(),
                "created_evidence_requests", response.createdEvidenceRequests().size(),
                "skipped_alerts", response.skippedAlerts().size()
            )
        );
        return response;
    }
}
