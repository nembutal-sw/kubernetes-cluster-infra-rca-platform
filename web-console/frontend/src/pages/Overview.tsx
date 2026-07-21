import { Icon, MetricTile, Surface } from "../components/common";
import {
  ClusterTopologyPreview,
  FailureSurface,
  OperationsReadinessPanel,
  RecentReport,
  SignalStream,
} from "../features/overview/OverviewPanels";
import { buildSignalDigest } from "../lib/consoleUtils";
import type {
  ClusterView,
  OverviewSummary,
  TFunction,
} from "../types";

type MaybePromise<T = void> = T | Promise<T>;

interface OverviewViewProps {
  summary: OverviewSummary;
  onNavigate: (view: string) => void;
  onOpenReport: (reportId: string) => void;
  onOpenCluster: (cluster: ClusterView) => MaybePromise;
  webhookEndpoint: string;
  t: TFunction;
}

export function OverviewView({
  summary,
  onNavigate,
  onOpenReport,
  onOpenCluster,
  webhookEndpoint,
  t,
}: OverviewViewProps) {
  const clusters = summary.recent_clusters;
  const reports = summary.recent_reports;
  const incidents = summary.recent_incidents;
  const pipelineBacklog = summary.analysis_backlog_count;
  const pendingApprovals = summary.pending_approval_count;
  const blockedActions = summary.blocked_action_count;
  const signalDigest = buildSignalDigest(reports, incidents);
  const latestReport = reports[0];

  return (
    <div className="page-stack">
      <section className="apm-hero">
        <div className="hero-copy">
          <p className="section-kicker">{t("APM-style infrastructure lens")}</p>
          <h1>{t("Cluster RCA Console")}</h1>
          <p>
            {t("Node pressure, kernel/runtime evidence, control-plane latency, and policy-gated remediation in one operational surface.")}
          </p>
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
        </div>
        <div className="hero-ops-board" aria-label={t("Operations readiness")}>
          <div className={summary.open_incident_count ? "hero-ops-row danger" : "hero-ops-row ok"}>
            <span>{t("Open incidents")}</span>
            <strong>{summary.open_incident_count}</strong>
          </div>
          <div className={pipelineBacklog ? "hero-ops-row warn" : "hero-ops-row ok"}>
            <span>{t("Analysis pipeline")}</span>
            <strong>{pipelineBacklog}</strong>
          </div>
          <div className={pendingApprovals || blockedActions ? "hero-ops-row warn" : "hero-ops-row ok"}>
            <span>{t("Policy queue")}</span>
            <strong>{pendingApprovals}/{blockedActions}</strong>
          </div>
        </div>
      </section>

      <section className="metric-grid">
        <MetricTile label={t("Open incidents")} value={summary.open_incident_count} tone={summary.open_incident_count ? "red" : "green"} icon="exclamation-diamond" />
        <MetricTile label={t("RCA reports")} value={summary.report_count} tone="blue" icon="clipboard2-pulse" />
        <MetricTile label={t("Registered clusters")} value={summary.cluster_count} tone="teal" icon="hdd-network" />
        <MetricTile label={t("Healthy agents")} value={summary.agent_count ? `${summary.healthy_agent_count}/${summary.agent_count}` : "n/a"} tone={summary.agent_count > summary.healthy_agent_count ? "amber" : "green"} icon="hdd-network" />
      </section>

      <OperationsReadinessPanel
        summary={summary}
        t={t}
      />

      <div className="dashboard-grid">
        <Surface
          title={t("Failure propagation")}
          subtitle={t("Evidence sequence by system layer")}
          action={<button className="btn btn-sm btn-outline-secondary" onClick={() => onNavigate("reports")}>{t("RCA Reports")}</button>}
        >
          <FailureSurface reports={reports} incidents={incidents} t={t} />
        </Surface>
        <Surface title={t("Signal stream")} subtitle={t("Prioritized recent infrastructure signals")}>
          <SignalStream items={signalDigest} t={t} />
        </Surface>
        <Surface title={t("Cluster topology")} subtitle={t("Registration and agent posture")}>
          <ClusterTopologyPreview clusters={clusters} onOpenCluster={onOpenCluster} t={t} />
        </Surface>
        <Surface
          title={t("Recent RCA")}
          subtitle={latestReport ? latestReport.report_id : t("No report selected")}
          action={<button className="btn btn-sm btn-outline-secondary" onClick={() => onNavigate("reports")}>{t("Open")}</button>}
        >
          <RecentReport report={latestReport} onOpenReport={onOpenReport} t={t} />
        </Surface>
      </div>

      <section className="ops-strip">
        <div>
          <span>{t("Webhook")}</span>
          <strong>{webhookEndpoint}</strong>
        </div>
        <div>
          <span>{t("Pipeline backlog")}</span>
          <strong>{pipelineBacklog}</strong>
        </div>
        <div>
          <span>{t("Action requests")}</span>
          <strong>{summary.action_request_count}</strong>
        </div>
        <div>
          <span>{t("Healthy agents")}</span>
          <strong>{summary.agent_count || "n/a"}</strong>
        </div>
      </section>
    </div>
  );
}
