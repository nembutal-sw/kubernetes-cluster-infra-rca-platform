import { SIGNAL_STAGES } from "../../constants";
import { EmptyState, Icon, StatusBadge } from "../../components/common";
import {
  buildSignalDigest,
  scoreStage,
  severityTone,
  signalIcon,
  summarizeAgentFleet,
  summarizePipeline,
  withinHours,
} from "../../lib/consoleUtils";
import type {
  ActionRequestView,
  AgentHealthView,
  AnalysisTaskView,
  ClusterView,
  IncidentView,
  RcaReport,
  TFunction,
} from "../../types";

type MaybePromise<T = void> = T | Promise<T>;
type SignalDigestItem = ReturnType<typeof buildSignalDigest>[number];

interface OperationsReadinessPanelProps {
  clusters: ClusterView[];
  reports: RcaReport[];
  incidents: IncidentView[];
  analysisTasks: AnalysisTaskView[];
  actionRequests: ActionRequestView[];
  agentHealth: AgentHealthView[];
  blockedActions: number;
  t: TFunction;
}

interface FailureSurfaceProps {
  reports: RcaReport[];
  incidents: IncidentView[];
  t: TFunction;
}

interface SignalStreamProps {
  items: SignalDigestItem[];
  t: TFunction;
}

interface ClusterTopologyPreviewProps {
  clusters: ClusterView[];
  onOpenCluster: (cluster: ClusterView) => MaybePromise;
  t: TFunction;
}

interface RecentReportProps {
  report?: RcaReport;
  onOpenReport: (reportId: string) => void;
  t: TFunction;
}

export function OperationsReadinessPanel({
  clusters,
  reports,
  incidents,
  analysisTasks,
  actionRequests,
  agentHealth,
  blockedActions,
  t,
}: OperationsReadinessPanelProps) {
  const fleet = summarizeAgentFleet(agentHealth, clusters);
  const pipeline = summarizePipeline(analysisTasks);
  const approvals = actionRequests.filter((item) => item.status === "pending_approval").length;
  const manual = actionRequests.filter((item) => ["accepted", "approved_manual"].includes(item.status || "")).length;
  const openIncidents = incidents.filter((item) => item.status === "open").length;
  const recentReports = reports.filter((report) => withinHours(report.created_at, 24)).length;
  const healthPercent = fleet.total ? Math.round((fleet.healthy / fleet.total) * 100) : 0;

  return (
    <section className="ops-readiness-grid" aria-label={t("Operations readiness")}>
      <article className={`ops-readiness-card ${fleet.unhealthy ? "warn" : "ok"}`}>
        <div className="ops-readiness-head">
          <span>{t("Agent fleet")}</span>
          <Icon name={fleet.unhealthy ? "exclamation-triangle" : "check2-circle"} />
        </div>
        <strong>{fleet.total ? `${healthPercent}% ${t("healthy")}` : t("No agents")}</strong>
        <div className="readiness-meter"><span style={{ width: `${healthPercent}%` }} /></div>
        <div className="mini-stat-row">
          <span>{t("Healthy agents")} <b>{fleet.healthy}</b></span>
          <span>{t("Stale")} <b>{fleet.stale}</b></span>
          <span>{t("Degraded")} <b>{fleet.degraded}</b></span>
          <span>{t("Offline")} <b>{fleet.offline}</b></span>
        </div>
      </article>
      <article className={`ops-readiness-card ${pipeline.deadLetter || pipeline.failed ? "danger" : pipeline.backlog ? "warn" : "ok"}`}>
        <div className="ops-readiness-head">
          <span>{t("Analysis pipeline")}</span>
          <Icon name="diagram-3" />
        </div>
        <strong>{pipeline.backlog} {t("active tasks")}</strong>
        <div className="mini-stat-row">
          <span>{t("Queued")} <b>{pipeline.queued}</b></span>
          <span>{t("Processing")} <b>{pipeline.processing}</b></span>
          <span>{t("Retry")} <b>{pipeline.retry}</b></span>
          <span>{t("Dead letter")} <b>{pipeline.deadLetter}</b></span>
        </div>
      </article>
      <article className={`ops-readiness-card ${approvals || blockedActions ? "warn" : "ok"}`}>
        <div className="ops-readiness-head">
          <span>{t("Policy queue")}</span>
          <Icon name="shield-lock" />
        </div>
        <strong>{approvals} {t("approvals pending")}</strong>
        <div className="mini-stat-row">
          <span>{t("Policy blocked")} <b>{blockedActions}</b></span>
          <span>{t("Manual")} <b>{manual}</b></span>
          <span>{t("Open incidents")} <b>{openIncidents}</b></span>
          <span>{t("Reports 24h")} <b>{recentReports}</b></span>
        </div>
      </article>
    </section>
  );
}

export function FailureSurface({ reports, incidents, t }: FailureSurfaceProps) {
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
          <strong>{t(stage.label)}</strong>
          <div className="stage-bar"><span style={{ width: `${Math.max(8, (stage.count / max) * 100)}%` }} /></div>
          <small>{stage.count} {t("signals")}</small>
          {index < counts.length - 1 && <div className="stage-link" />}
        </div>
      ))}
    </div>
  );
}

export function SignalStream({ items, t }: SignalStreamProps) {
  if (!items.length) return <EmptyState message={t("New node or control-plane evidence will appear here.")} />;
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

export function ClusterTopologyPreview({ clusters, onOpenCluster, t }: ClusterTopologyPreviewProps) {
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

export function RecentReport({ report, onOpenReport, t }: RecentReportProps) {
  if (!report) return <EmptyState message={t("No reports loaded.")} />;
  return (
    <article className="recent-report">
      <div className="report-cause">
        <span>{report.summary?.confidence || "unknown"}</span>
        <strong>{report.summary?.most_likely_cause || report.summary?.symptom}</strong>
      </div>
      <p>{(report.root_cause_candidates || [])[0]?.supporting_evidence?.[0] || t("No evidence summary available.")}</p>
      <button className="btn btn-sm btn-outline-secondary" onClick={() => onOpenReport(report.report_id)}>{t("Report detail")}</button>
    </article>
  );
}
