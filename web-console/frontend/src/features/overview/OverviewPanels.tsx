import { SIGNAL_STAGES } from "../../constants";
import { EmptyState, Icon, StatusBadge } from "../../components/common";
import {
  buildSignalDigest,
  scoreStage,
  severityTone,
  signalIcon,
} from "../../lib/consoleUtils";
import type {
  ClusterView,
  IncidentView,
  OverviewSummary,
  RcaReport,
  TFunction,
} from "../../types";

type MaybePromise<T = void> = T | Promise<T>;
type SignalDigestItem = ReturnType<typeof buildSignalDigest>[number];

interface OperationsReadinessPanelProps {
  summary: OverviewSummary;
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
  summary,
  t,
}: OperationsReadinessPanelProps) {
  const healthPercent = summary.agent_count
    ? Math.round((summary.healthy_agent_count / summary.agent_count) * 100)
    : 0;

  return (
    <section className="ops-readiness-grid" aria-label={t("Operations readiness")}>
      <article className={`ops-readiness-card ${summary.agent_count > summary.healthy_agent_count ? "warn" : "ok"}`}>
        <div className="ops-readiness-head">
          <span>{t("Agent fleet")}</span>
          <Icon name={summary.agent_count > summary.healthy_agent_count ? "exclamation-triangle" : "check2-circle"} />
        </div>
        <strong>{summary.agent_count ? `${healthPercent}% ${t("healthy")}` : t("No agents")}</strong>
        <div className="readiness-meter"><span style={{ width: `${healthPercent}%` }} /></div>
        <div className="mini-stat-row">
          <span>{t("Healthy agents")} <b>{summary.healthy_agent_count}</b></span>
          <span>{t("Stale")} <b>{summary.stale_agent_count}</b></span>
          <span>{t("Degraded")} <b>{summary.degraded_agent_count}</b></span>
          <span>{t("Offline")} <b>{summary.offline_agent_count}</b></span>
        </div>
      </article>
      <article className={`ops-readiness-card ${summary.analysis_dead_letter_count ? "danger" : summary.analysis_backlog_count ? "warn" : "ok"}`}>
        <div className="ops-readiness-head">
          <span>{t("Analysis pipeline")}</span>
          <Icon name="diagram-3" />
        </div>
        <strong>{summary.analysis_backlog_count} {t("active tasks")}</strong>
        <div className="mini-stat-row">
          <span>{t("Queued")} <b>{summary.analysis_queued_count}</b></span>
          <span>{t("Processing")} <b>{summary.analysis_processing_count}</b></span>
          <span>{t("Retry")} <b>{summary.analysis_retry_count}</b></span>
          <span>{t("Dead letter")} <b>{summary.analysis_dead_letter_count}</b></span>
        </div>
      </article>
      <article className={`ops-readiness-card ${summary.pending_approval_count || summary.blocked_action_count ? "warn" : "ok"}`}>
        <div className="ops-readiness-head">
          <span>{t("Policy queue")}</span>
          <Icon name="shield-lock" />
        </div>
        <strong>{summary.pending_approval_count} {t("approvals pending")}</strong>
        <div className="mini-stat-row">
          <span>{t("Policy blocked")} <b>{summary.blocked_action_count}</b></span>
          <span>{t("Manual")} <b>{summary.manual_action_count}</b></span>
          <span>{t("Open incidents")} <b>{summary.open_incident_count}</b></span>
          <span>{t("Reports 24h")} <b>{summary.reports_last_24_hours}</b></span>
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
