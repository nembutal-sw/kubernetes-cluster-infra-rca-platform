import { Icon, MetricTile, Surface } from "../components/common";
import {
  ClusterTopologyPreview,
  FailureSurface,
  OperationsReadinessPanel,
  RecentReport,
  SignalStream,
} from "../features/overview/OverviewPanels";
import { buildSignalDigest, summarizeAgentFleet } from "../lib/consoleUtils";
import type {
  ActionRequestView,
  AgentHealthView,
  AnalysisTaskView,
  ClusterView,
  IncidentView,
  RcaReport,
  TFunction,
} from "../types";

type MaybePromise<T = void> = T | Promise<T>;

interface OverviewViewProps {
  clusters: ClusterView[];
  reports: RcaReport[];
  incidents: IncidentView[];
  analysisTasks: AnalysisTaskView[];
  actionRequests: ActionRequestView[];
  agentHealth: AgentHealthView[];
  onNavigate: (view: string) => void;
  onOpenReport: (reportId: string) => void;
  onOpenCluster: (cluster: ClusterView) => MaybePromise;
  webhookEndpoint: string;
  t: TFunction;
}

export function OverviewView({
  clusters,
  reports,
  incidents,
  analysisTasks,
  actionRequests,
  agentHealth,
  onNavigate,
  onOpenReport,
  onOpenCluster,
  webhookEndpoint,
  t,
}: OverviewViewProps) {
  const openIncidents = incidents.filter((item) => item.status === "open");
  const fleet = summarizeAgentFleet(agentHealth, clusters);
  const agents = fleet.total || clusters.reduce((acc, cluster) => acc + Number(cluster.agent_count || 0), 0);
  const blockedActions = reports
    .flatMap((report) => report.recommended_actions || [])
    .filter((action) => action.automation_allowed !== true).length;
  const pipelineBacklog = analysisTasks.filter((task) => ["queued", "processing", "retry_wait"].includes(task.status || "")).length;
  const pendingApprovals = actionRequests.filter((request) => request.status === "pending_approval").length;
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
          <div className={openIncidents.length ? "hero-ops-row danger" : "hero-ops-row ok"}>
            <span>{t("Open incidents")}</span>
            <strong>{openIncidents.length}</strong>
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
