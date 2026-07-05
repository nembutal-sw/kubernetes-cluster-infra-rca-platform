// @ts-nocheck

import { SIGNAL_STAGES } from "../constants";

import { EmptyState, Icon, MetricTile, PageHeader, ResponsiveTable, StatusBadge, Surface } from "../components/common";

import { arrayResult, sortByTime, copyText, buildAuditQuery, auditStats, buildSignalDigest, scoreStage, occurrences, escapeRegExp, inferSignalFamily, evidenceSummary, derivedSignals, reportEvidenceQuality, reportQualityGate, qualityTone, qualityGateTone, formatFreshness, formatPercentValue, fallbackTimeline, shortValue, platformInfoRows, formatBytes, shortHash, formatDate, runConsoleLayoutAudit, layoutElementLabel, layoutElementText, relativeTime, statusTone, policyTone, confidenceTone, severityTone, requestTone, taskTone, summarizeAgentFleet, normalizedAgentStatus, agentReason, summarizePipeline, withinHours, auditTone, agentHealthTone, signalIcon, auditClientIp, auditSummary } from "../lib/consoleUtils";

export function OverviewView({ clusters, reports, incidents, analysisTasks, actionRequests, agentHealth, onNavigate, onOpenReport, onOpenCluster, webhookEndpoint, t }) {
  const openIncidents = incidents.filter((item) => item.status === "open");
  const fleet = summarizeAgentFleet(agentHealth, clusters);
  const agents = fleet.total || clusters.reduce((acc, cluster) => acc + Number(cluster.agent_count || 0), 0);
  const blockedActions = reports.flatMap((report) => report.recommended_actions || []).filter((action) => action.automation_allowed !== true).length;
  const signalDigest = buildSignalDigest(reports, incidents);
  const latestReport = reports[0];
  return (
    <div className="page-stack">
      <section className="apm-hero">
        <div>
          <p className="section-kicker">APM-style infrastructure lens</p>
          <h1>{t("APM Failure Surface")}</h1>
          <p>
            Node pressure, kernel/runtime evidence, control-plane latency, and policy-gated remediation in one operational surface.
          </p>
        </div>
        <div className="hero-actions">
          <button className="btn btn-light btn-sm icon-button" onClick={() => onNavigate("reports")}>
            <Icon name="clipboard2-pulse" />
            <span>{t("RCA Reports")}</span>
          </button>
          <button className="btn btn-outline-light btn-sm icon-button" onClick={() => onNavigate("clusters")}>
            <Icon name="hdd-network" />
            <span>{t("Clusters")}</span>
          </button>
        </div>
      </section>

      <section className="metric-grid">
        <MetricTile label={t("Open incidents")} value={openIncidents.length} tone={openIncidents.length ? "red" : "green"} icon="exclamation-diamond" />
        <MetricTile label={t("RCA reports")} value={reports.length} tone="blue" icon="clipboard2-pulse" />
        <MetricTile label={t("Registered clusters")} value={clusters.length} tone="teal" icon="hdd-network" />
        <MetricTile label={t("Healthy agents")} value={fleet.total ? `${fleet.healthy}/${fleet.total}` : "n/a"} tone={fleet.unhealthy ? "amber" : "green"} icon="hdd-network" />
      </section>

      <OperationsReadinessPanel
        clusters={clusters}
        reports={reports}
        incidents={incidents}
        analysisTasks={analysisTasks}
        actionRequests={actionRequests}
        agentHealth={agentHealth}
        blockedActions={blockedActions}
        t={t}
      />

      <div className="dashboard-grid">
        <Surface title={t("Failure propagation")} subtitle="Evidence sequence by system layer" action={<button className="btn btn-sm btn-outline-secondary" onClick={() => onNavigate("reports")}>{t("RCA Reports")}</button>}>
          <FailureSurface reports={reports} incidents={incidents} t={t} />
        </Surface>
        <Surface title={t("Signal stream")} subtitle="Prioritized recent infrastructure signals">
          <SignalStream items={signalDigest} t={t} />
        </Surface>
        <Surface title={t("Cluster topology")} subtitle="Registration and agent posture">
          <ClusterTopologyPreview clusters={clusters} onOpenCluster={onOpenCluster} t={t} />
        </Surface>
        <Surface title={t("Recent RCA")} subtitle={latestReport ? latestReport.report_id : "No report selected"} action={<button className="btn btn-sm btn-outline-secondary" onClick={() => onNavigate("reports")}>Open</button>}>
          <RecentReport report={latestReport} onOpenReport={onOpenReport} t={t} />
        </Surface>
      </div>

      <section className="ops-strip">
        <div>
          <span>Webhook</span>
          <strong>{webhookEndpoint}</strong>
        </div>
        <div>
          <span>Pipeline backlog</span>
          <strong>{analysisTasks.filter((task) => ["queued", "processing", "retry_wait"].includes(task.status)).length}</strong>
        </div>
        <div>
          <span>{t("Action requests")}</span>
          <strong>{actionRequests.length}</strong>
        </div>
        <div>
          <span>{t("Healthy agents")}</span>
          <strong>{agents || "n/a"}</strong>
        </div>
      </section>
    </div>
  );
}

export function OperationsReadinessPanel({ clusters, reports, incidents, analysisTasks, actionRequests, agentHealth, blockedActions, t }) {
  const fleet = summarizeAgentFleet(agentHealth, clusters);
  const pipeline = summarizePipeline(analysisTasks);
  const approvals = actionRequests.filter((item) => item.status === "pending_approval").length;
  const manual = actionRequests.filter((item) => ["accepted", "approved_manual"].includes(item.status)).length;
  const openIncidents = incidents.filter((item) => item.status === "open").length;
  const recentReports = reports.filter((report) => withinHours(report.created_at, 24)).length;
  const healthPercent = fleet.total ? Math.round((fleet.healthy / fleet.total) * 100) : 0;
  return (
    <section className="ops-readiness-grid" aria-label="Operations readiness">
      <article className={`ops-readiness-card ${fleet.unhealthy ? "warn" : "ok"}`}>
        <div className="ops-readiness-head">
          <span>{t("Agent fleet")}</span>
          <Icon name={fleet.unhealthy ? "exclamation-triangle" : "check2-circle"} />
        </div>
        <strong>{fleet.total ? `${healthPercent}% healthy` : "No agents"}</strong>
        <div className="readiness-meter"><span style={{ width: `${healthPercent}%` }} /></div>
        <div className="mini-stat-row">
          <span>{t("Healthy agents")} <b>{fleet.healthy}</b></span>
          <span>Stale <b>{fleet.stale}</b></span>
          <span>Degraded <b>{fleet.degraded}</b></span>
          <span>Offline <b>{fleet.offline}</b></span>
        </div>
      </article>
      <article className={`ops-readiness-card ${pipeline.deadLetter || pipeline.failed ? "danger" : pipeline.backlog ? "warn" : "ok"}`}>
        <div className="ops-readiness-head">
          <span>{t("Analysis pipeline")}</span>
          <Icon name="diagram-3" />
        </div>
        <strong>{pipeline.backlog} active tasks</strong>
        <div className="mini-stat-row">
          <span>Queued <b>{pipeline.queued}</b></span>
          <span>Processing <b>{pipeline.processing}</b></span>
          <span>Retry <b>{pipeline.retry}</b></span>
          <span>Dead letter <b>{pipeline.deadLetter}</b></span>
        </div>
      </article>
      <article className={`ops-readiness-card ${approvals || blockedActions ? "warn" : "ok"}`}>
        <div className="ops-readiness-head">
          <span>{t("Policy queue")}</span>
          <Icon name="shield-lock" />
        </div>
        <strong>{approvals} approvals pending</strong>
        <div className="mini-stat-row">
          <span>{t("Policy blocked")} <b>{blockedActions}</b></span>
          <span>Manual <b>{manual}</b></span>
          <span>{t("Open incidents")} <b>{openIncidents}</b></span>
          <span>Reports 24h <b>{recentReports}</b></span>
        </div>
      </article>
    </section>
  );
}

export function FailureSurface({ reports, incidents }) {
  const counts = SIGNAL_STAGES.map((stage) => ({
    ...stage,
    count: scoreStage(stage.key, reports, incidents),
  }));
  const max = Math.max(1, ...counts.map((item) => item.count));
  return (
    <div className="failure-surface">
      {counts.map((stage, index) => (
        <div key={stage.key} className={`surface-stage ${stage.count ? "hot" : ""}`}>
          <div className="stage-icon"><Icon name={stage.icon} /></div>
          <strong>{stage.label}</strong>
          <div className="stage-bar"><span style={{ width: `${Math.max(8, (stage.count / max) * 100)}%` }} /></div>
          <small>{stage.count} signals</small>
          {index < counts.length - 1 && <div className="stage-link" />}
        </div>
      ))}
    </div>
  );
}

export function SignalStream({ items, t }) {
  if (!items.length) return <EmptyState message="New node or control-plane evidence will appear here." />;
  return (
    <div className="signal-list">
      {items.slice(0, 8).map((item) => (
        <article key={item.id} className="signal-row">
          <Icon name={signalIcon(item.family)} />
          <div>
            <strong>{item.title}</strong>
            <span>{item.detail}</span>
          </div>
          <StatusBadge value={item.severity || "info"} tone={severityTone(item.severity)} t={t} />
        </article>
      ))}
    </div>
  );
}

export function ClusterTopologyPreview({ clusters, onOpenCluster, t }) {
  if (!clusters.length) return <EmptyState message={t("No clusters registered.")} />;
  return (
    <div className="mini-topology">
      {clusters.slice(0, 10).map((cluster) => (
        <button key={cluster.cluster_id} onClick={() => onOpenCluster(cluster)}>
          <Icon name="hdd-network" />
          <strong>{cluster.name}</strong>
          <StatusBadge value={cluster.status} tone={cluster.status === "active" ? "green" : "amber"} t={t} />
        </button>
      ))}
    </div>
  );
}

export function RecentReport({ report, onOpenReport, t }) {
  if (!report) return <EmptyState message={t("No reports loaded.")} />;
  return (
    <article className="recent-report">
      <div className="report-cause">
        <span>{report.summary?.confidence || "unknown"}</span>
        <strong>{report.summary?.most_likely_cause || report.summary?.symptom}</strong>
      </div>
      <p>{(report.root_cause_candidates || [])[0]?.supporting_evidence?.[0] || "No evidence summary available."}</p>
      <button className="btn btn-sm btn-outline-secondary" onClick={() => onOpenReport(report.report_id)}>{t("Report detail")}</button>
    </article>
  );
}
