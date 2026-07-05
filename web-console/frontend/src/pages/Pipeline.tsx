// @ts-nocheck

import { useEffect, useState } from "react";

import { EmptyState, Icon, MetricTile, PageHeader, ResponsiveTable, StatusBadge, Surface } from "../components/common";

import { arrayResult, sortByTime, copyText, buildAuditQuery, auditStats, buildSignalDigest, scoreStage, occurrences, escapeRegExp, inferSignalFamily, evidenceSummary, derivedSignals, reportEvidenceQuality, reportQualityGate, qualityTone, qualityGateTone, formatFreshness, formatPercentValue, fallbackTimeline, shortValue, platformInfoRows, formatBytes, shortHash, formatDate, runConsoleLayoutAudit, layoutElementLabel, layoutElementText, relativeTime, statusTone, policyTone, confidenceTone, severityTone, requestTone, taskTone, summarizeAgentFleet, normalizedAgentStatus, agentReason, summarizePipeline, withinHours, auditTone, agentHealthTone, signalIcon, auditClientIp, auditSummary } from "../lib/consoleUtils";

export function PipelineView({ tasks, actionRequests, demoScenarios, clusters, agentHealth, onRetry, onRunDemo, t }) {
  const pipeline = summarizePipeline(tasks);
  const fleet = summarizeAgentFleet(agentHealth, clusters);
  const pendingApprovals = actionRequests.filter((item) => item.status === "pending_approval").length;
  const blockedRequests = actionRequests.filter((item) => ["blocked", "rejected"].includes(item.status)).length;
  return (
    <div className="page-stack">
      <PageHeader title={t("Pipeline")} subtitle="Analysis worker, approval queue, and built-in RCA scenario generator." />
      <section className="pipeline-stats">
        <MetricTile label="Active tasks" value={pipeline.backlog} tone={pipeline.backlog ? "amber" : "green"} icon="cpu" />
        <MetricTile label="Dead letter" value={pipeline.deadLetter} tone={pipeline.deadLetter ? "red" : "green"} icon="exclamation-octagon" />
        <MetricTile label="Pending approvals" value={pendingApprovals} tone={pendingApprovals ? "amber" : "green"} icon="person-check" />
        <MetricTile label="Blocked requests" value={blockedRequests} tone={blockedRequests ? "red" : "green"} icon="shield-lock" />
        <MetricTile label={t("Healthy agents")} value={fleet.total ? `${fleet.healthy}/${fleet.total}` : "n/a"} tone={fleet.unhealthy ? "amber" : "green"} icon="hdd-network" />
      </section>
      <div className="split-grid">
        <Surface title={t("Analysis tasks")} subtitle={`${tasks.length} tasks`}>
          <TaskList tasks={tasks} onRetry={onRetry} t={t} />
        </Surface>
        <Surface title={t("Action requests")} subtitle={`${actionRequests.length} requests`}>
          <RequestQueue items={actionRequests} t={t} />
        </Surface>
      </div>
      <Surface title={t("Demo scenarios")} subtitle="Generate realistic evidence without Prometheus">
        <DemoScenarios scenarios={demoScenarios} clusters={clusters} onRunDemo={onRunDemo} t={t} />
      </Surface>
    </div>
  );
}

export function TaskList({ tasks, onRetry, t }) {
  if (!tasks.length) return <EmptyState message="No analysis tasks." />;
  return (
    <div className="task-list">
      {tasks.slice(0, 30).map((task) => (
        <article key={task.task_id} className="task-item">
          <div>
            <strong>{task.alert_name || task.task_id}</strong>
            <span>{task.cluster_id} / {task.node_name || "cluster"}</span>
          </div>
          <StatusBadge value={task.status} tone={taskTone(task.status)} t={t} />
          {["failed", "dead_letter"].includes(task.status) && <button className="btn btn-sm btn-outline-secondary" onClick={() => onRetry(task)}>{t("Retry")}</button>}
        </article>
      ))}
    </div>
  );
}

export function RequestQueue({ items, t }) {
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

export function DemoScenarios({ scenarios, clusters, onRunDemo, t }) {
  const [clusterId, setClusterId] = useState(clusters[0]?.cluster_id || "");
  const [nodeName, setNodeName] = useState("demo-worker-01");
  useEffect(() => {
    if (!clusterId && clusters[0]?.cluster_id) setClusterId(clusters[0].cluster_id);
  }, [clusterId, clusters]);
  if (!scenarios.length) return <EmptyState message="No demo scenarios." />;
  return (
    <div className="scenario-grid">
      {scenarios.map((scenario) => (
        <article key={scenario.key} className="scenario-card">
          <h3>{scenario.name}</h3>
          <p>{scenario.description}</p>
          <div className="scenario-controls">
            <select className="form-select form-select-sm" value={clusterId} onChange={(event) => setClusterId(event.target.value)}>
              <option value="">Auto demo cluster</option>
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
