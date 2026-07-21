import { useEffect, useState } from "react";

import { EmptyState, Icon, MetricTile, PageHeader, StatusBadge, Surface } from "../components/common";
import { CursorPager } from "../components/CursorPager";
import { useCursorPage, useDebouncedValue } from "../hooks/useCursorPage";
import { requestTone, taskTone } from "../lib/consoleUtils";
import type {
  ActionRequestView,
  AnalysisTaskView,
  ApiCall,
  ClusterView,
  DemoScenarioView,
  OverviewSummary,
  TFunction,
} from "../types";

type MaybePromise<T = void> = T | Promise<T>;

interface PipelineViewProps {
  callApi: ApiCall;
  refreshToken?: string;
  summary: OverviewSummary;
  actionRequests: ActionRequestView[];
  demoScenarios: DemoScenarioView[];
  clusters: ClusterView[];
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

export function PipelineView({ callApi, refreshToken, summary, actionRequests, demoScenarios, clusters, onRetry, onRunDemo, t }: PipelineViewProps) {
  const [query, setQuery] = useState("");
  const [clusterId, setClusterId] = useState("");
  const [status, setStatus] = useState("");
  const debouncedQuery = useDebouncedValue(query);
  const taskResult = useCursorPage<AnalysisTaskView>(callApi, "/api/v1/rca/analysis-tasks", {
    q: debouncedQuery,
    cluster_id: clusterId,
    status,
  }, refreshToken, 30);

  return (
    <div className="page-stack">
      <PageHeader title={t("Pipeline")} subtitle={t("Analysis worker, approval queue, and built-in RCA scenario generator.")} />
      <section className="pipeline-stats">
        <MetricTile label={t("Active tasks")} value={summary.analysis_backlog_count} tone={summary.analysis_backlog_count ? "amber" : "green"} icon="cpu" />
        <MetricTile label={t("Dead letter")} value={summary.analysis_dead_letter_count} tone={summary.analysis_dead_letter_count ? "red" : "green"} icon="exclamation-octagon" />
        <MetricTile label={t("Pending approvals")} value={summary.pending_approval_count} tone={summary.pending_approval_count ? "amber" : "green"} icon="person-check" />
        <MetricTile label={t("Blocked requests")} value={summary.blocked_action_count} tone={summary.blocked_action_count ? "red" : "green"} icon="shield-lock" />
        <MetricTile label={t("Healthy agents")} value={summary.agent_count ? `${summary.healthy_agent_count}/${summary.agent_count}` : "n/a"} tone={summary.agent_count > summary.healthy_agent_count ? "amber" : "green"} icon="hdd-network" />
      </section>
      <div className="split-grid">
        <Surface title={t("Analysis tasks")} subtitle={`${taskResult.page.total} ${t("tasks")}`}>
          <div className="ops-filter-bar compact">
            <div className="input-group input-group-sm ops-search-control">
              <span className="input-group-text"><Icon name="search" /></span>
              <input className="form-control" value={query} onChange={(event) => setQuery(event.target.value)} placeholder={t("Search tasks")} aria-label={t("Search tasks")} />
            </div>
            <select className="form-select form-select-sm" value={clusterId} onChange={(event) => setClusterId(event.target.value)} aria-label={t("Filter by cluster")}>
              <option value="">{t("All clusters")}</option>
              {clusters.map((cluster) => <option key={cluster.cluster_id} value={cluster.cluster_id}>{cluster.name}</option>)}
            </select>
            <select className="form-select form-select-sm" value={status} onChange={(event) => setStatus(event.target.value)} aria-label={t("Filter by status")}>
              <option value="">{t("All statuses")}</option>
              {["queued", "processing", "retry_wait", "completed", "skipped", "dead_letter"].map((value) => <option key={value} value={value}>{t(value)}</option>)}
            </select>
          </div>
          {taskResult.error && <div className="alert alert-warning py-2">{taskResult.error.detail}</div>}
          <TaskList tasks={taskResult.page.items} onRetry={onRetry} t={t} />
          <CursorPager page={taskResult.pageNumber} total={taskResult.page.total} loading={taskResult.loading} canPrevious={taskResult.canPrevious} canNext={taskResult.canNext} onPrevious={taskResult.previous} onNext={taskResult.next} t={t} />
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
        <article key={scenario.key} className="scenario-card" data-testid={`scenario-${scenario.key}`}>
          <h3>{scenario.name}</h3>
          <p>{scenario.description}</p>
          <div className="scenario-controls">
            <select className="form-select form-select-sm" value={clusterId} onChange={(event) => setClusterId(event.target.value)}>
              <option value="">{t("Auto demo cluster")}</option>
              {clusters.map((cluster) => <option key={cluster.cluster_id} value={cluster.cluster_id}>{cluster.name}</option>)}
            </select>
            <input className="form-control form-control-sm" value={nodeName} onChange={(event) => setNodeName(event.target.value)} />
            <button className="btn btn-sm btn-primary" data-testid="scenario-run" onClick={() => onRunDemo(scenario, clusterId, nodeName)}>{t("Run")}</button>
          </div>
        </article>
      ))}
    </div>
  );
}
