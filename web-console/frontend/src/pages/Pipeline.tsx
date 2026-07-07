import { useEffect, useState } from "react";

import { EmptyState, MetricTile, PageHeader, StatusBadge, Surface } from "../components/common";
import { requestTone, summarizeAgentFleet, summarizePipeline, taskTone } from "../lib/consoleUtils";
import type {
  ActionRequestView,
  AgentHealthView,
  AnalysisTaskView,
  ClusterView,
  DemoScenarioView,
  TFunction,
} from "../types";

type MaybePromise<T = void> = T | Promise<T>;

interface PipelineViewProps {
  tasks: AnalysisTaskView[];
  actionRequests: ActionRequestView[];
  demoScenarios: DemoScenarioView[];
  clusters: ClusterView[];
  agentHealth: AgentHealthView[];
  onRetry: (task: AnalysisTaskView) => MaybePromise;
  onRunDemo: (scenario: DemoScenarioView, clusterId: string, nodeName: string) => MaybePromise;
  t: TFunction;
}

interface TaskListProps {
  tasks: AnalysisTaskView[];
  onRetry: (task: AnalysisTaskView) => MaybePromise;
  t: TFunction;
}

interface RequestQueueProps {
  items: ActionRequestView[];
  t: TFunction;
}

interface DemoScenariosProps {
  scenarios: DemoScenarioView[];
  clusters: ClusterView[];
  onRunDemo: (scenario: DemoScenarioView, clusterId: string, nodeName: string) => MaybePromise;
  t: TFunction;
}

export function PipelineView({ tasks, actionRequests, demoScenarios, clusters, agentHealth, onRetry, onRunDemo, t }: PipelineViewProps) {
  const pipeline = summarizePipeline(tasks);
  const fleet = summarizeAgentFleet(agentHealth, clusters);
  const pendingApprovals = actionRequests.filter((item) => item.status === "pending_approval").length;
  const blockedRequests = actionRequests.filter((item) => ["blocked", "rejected"].includes(item.status || "")).length;

  return (
    <div className="page-stack">
      <PageHeader title={t("Pipeline")} subtitle={t("Analysis worker, approval queue, and built-in RCA scenario generator.")} />
      <section className="pipeline-stats">
        <MetricTile label={t("Active tasks")} value={pipeline.backlog} tone={pipeline.backlog ? "amber" : "green"} icon="cpu" />
        <MetricTile label={t("Dead letter")} value={pipeline.deadLetter} tone={pipeline.deadLetter ? "red" : "green"} icon="exclamation-octagon" />
        <MetricTile label={t("Pending approvals")} value={pendingApprovals} tone={pendingApprovals ? "amber" : "green"} icon="person-check" />
        <MetricTile label={t("Blocked requests")} value={blockedRequests} tone={blockedRequests ? "red" : "green"} icon="shield-lock" />
        <MetricTile label={t("Healthy agents")} value={fleet.total ? `${fleet.healthy}/${fleet.total}` : "n/a"} tone={fleet.unhealthy ? "amber" : "green"} icon="hdd-network" />
      </section>
      <div className="split-grid">
        <Surface title={t("Analysis tasks")} subtitle={`${tasks.length} ${t("tasks")}`}>
          <TaskList tasks={tasks} onRetry={onRetry} t={t} />
        </Surface>
        <Surface title={t("Action requests")} subtitle={`${actionRequests.length} ${t("requests")}`}>
          <RequestQueue items={actionRequests} t={t} />
        </Surface>
      </div>
      <Surface title={t("Demo scenarios")} subtitle={t("Generate realistic evidence without Prometheus")}>
        <DemoScenarios scenarios={demoScenarios} clusters={clusters} onRunDemo={onRunDemo} t={t} />
      </Surface>
    </div>
  );
}

export function TaskList({ tasks, onRetry, t }: TaskListProps) {
  if (!tasks.length) return <EmptyState message={t("No analysis tasks.")} />;
  return (
    <div className="task-list">
      {tasks.slice(0, 30).map((task) => (
        <article key={task.task_id} className="task-item">
          <div>
            <strong>{task.alert_name || task.task_id}</strong>
            <span>{task.cluster_id} / {task.node_name || "cluster"}</span>
          </div>
          <StatusBadge value={task.status} tone={taskTone(task.status)} t={t} />
          {["failed", "dead_letter"].includes(task.status || "") && (
            <button className="btn btn-sm btn-outline-secondary" onClick={() => onRetry(task)}>{t("Retry")}</button>
          )}
        </article>
      ))}
    </div>
  );
}

export function RequestQueue({ items, t }: RequestQueueProps) {
  if (!items.length) return <EmptyState message={t("No action requests.")} />;
  return (
    <div className="task-list">
      {items.slice(0, 30).map((item) => (
        <article key={item.action_request_id} className="task-item">
          <div>
            <strong>{item.action_key}</strong>
            <span>{item.report_id}</span>
          </div>
          <StatusBadge value={item.status} tone={requestTone(item.status)} t={t} />
        </article>
      ))}
    </div>
  );
}

export function DemoScenarios({ scenarios, clusters, onRunDemo, t }: DemoScenariosProps) {
  const [clusterId, setClusterId] = useState(clusters[0]?.cluster_id || "");
  const [nodeName, setNodeName] = useState("demo-worker-01");

  useEffect(() => {
    if (!clusterId && clusters[0]?.cluster_id) setClusterId(clusters[0].cluster_id);
  }, [clusterId, clusters]);

  if (!scenarios.length) return <EmptyState message={t("No demo scenarios.")} />;
  return (
    <div className="scenario-grid">
      {scenarios.map((scenario) => (
        <article key={scenario.key} className="scenario-card">
          <h3>{scenario.name}</h3>
          <p>{scenario.description}</p>
          <div className="scenario-controls">
            <select className="form-select form-select-sm" value={clusterId} onChange={(event) => setClusterId(event.target.value)}>
              <option value="">{t("Auto demo cluster")}</option>
              {clusters.map((cluster) => <option key={cluster.cluster_id} value={cluster.cluster_id}>{cluster.name}</option>)}
            </select>
            <input className="form-control form-control-sm" value={nodeName} onChange={(event) => setNodeName(event.target.value)} />
            <button className="btn btn-sm btn-primary" onClick={() => onRunDemo(scenario, clusterId, nodeName)}>{t("Run")}</button>
          </div>
        </article>
      ))}
    </div>
  );
}
