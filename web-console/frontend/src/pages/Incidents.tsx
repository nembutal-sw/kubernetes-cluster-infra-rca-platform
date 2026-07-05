// @ts-nocheck

import { EmptyState, Icon, MetricTile, PageHeader, ResponsiveTable, StatusBadge, Surface } from "../components/common";

import { arrayResult, sortByTime, copyText, buildAuditQuery, auditStats, buildSignalDigest, scoreStage, occurrences, escapeRegExp, inferSignalFamily, evidenceSummary, derivedSignals, reportEvidenceQuality, reportQualityGate, qualityTone, qualityGateTone, formatFreshness, formatPercentValue, fallbackTimeline, shortValue, platformInfoRows, formatBytes, shortHash, formatDate, runConsoleLayoutAudit, layoutElementLabel, layoutElementText, relativeTime, statusTone, policyTone, confidenceTone, severityTone, requestTone, taskTone, summarizeAgentFleet, normalizedAgentStatus, agentReason, summarizePipeline, withinHours, auditTone, agentHealthTone, signalIcon, auditClientIp, auditSummary } from "../lib/consoleUtils";

export function IncidentsView({ incidents, onOpenReport, onChangeStatus, currentUser, t }) {
  const canOperate = ["admin", "operator"].includes(currentUser.role);
  return (
    <div className="page-stack">
      <PageHeader title={t("Incidents")} subtitle="Correlated evidence grouped by node, cause, and recurrence." />
      <Surface title={t("Incidents")} subtitle={`${incidents.length} total`}>
        <div className="incident-list">
          {incidents.length ? incidents.map((incident) => (
            <article key={incident.incident_id} className="incident-item">
              <div>
                <StatusBadge value={incident.status} tone={incident.status === "open" ? "red" : "green"} t={t} />
                <h3>{incident.alert_name}</h3>
                <p>{incident.root_cause || "Root cause not available yet."}</p>
                <div className="meta-row">
                  <span>{incident.cluster_id}</span>
                  <span>{(incident.node_names || [incident.node_name]).filter(Boolean).join(", ")}</span>
                  <span>{incident.occurrence_count}x</span>
                </div>
              </div>
              <div className="incident-actions">
                {incident.latest_report_id && <button className="btn btn-sm btn-outline-secondary" onClick={() => onOpenReport(incident.latest_report_id)}>{t("RCA Reports")}</button>}
                {canOperate && incident.status === "open" && <button className="btn btn-sm btn-success" onClick={() => onChangeStatus(incident, "resolve")}>Resolve</button>}
                {canOperate && incident.status === "resolved" && <button className="btn btn-sm btn-outline-secondary" onClick={() => onChangeStatus(incident, "reopen")}>Reopen</button>}
              </div>
            </article>
          )) : <EmptyState message={t("No incidents loaded.")} />}
        </div>
      </Surface>
    </div>
  );
}
