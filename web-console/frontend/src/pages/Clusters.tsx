// @ts-nocheck

import { useEffect, useState } from "react";

import { EmptyState, Icon, MetricTile, PageHeader, ResponsiveTable, StatusBadge, Surface } from "../components/common";

import { arrayResult, sortByTime, copyText, buildAuditQuery, auditStats, buildSignalDigest, scoreStage, occurrences, escapeRegExp, inferSignalFamily, evidenceSummary, derivedSignals, reportEvidenceQuality, reportQualityGate, qualityTone, qualityGateTone, formatFreshness, formatPercentValue, fallbackTimeline, shortValue, platformInfoRows, formatBytes, shortHash, formatDate, runConsoleLayoutAudit, layoutElementLabel, layoutElementText, relativeTime, statusTone, policyTone, confidenceTone, severityTone, requestTone, taskTone, summarizeAgentFleet, normalizedAgentStatus, agentReason, summarizePipeline, withinHours, auditTone, agentHealthTone, signalIcon, auditClientIp, auditSummary } from "../lib/consoleUtils";

export function ClustersView(props) {
  const {
    clusters,
    selectedCluster,
    clusterDetail,
    installCommand,
    currentUser,
    onCreate,
    onSelect,
    onGenerateInstall,
    onStartCollection,
    onDelete,
    onRotateToken,
    onCopy,
    t,
  } = props;
  const canOperate = ["admin", "operator"].includes(currentUser.role);
  return (
    <div className="page-stack">
      <PageHeader title={t("Clusters")} subtitle="Register clusters, install node agents, and inspect collected evidence." />
      <div className="split-grid">
        <Surface title={t("Create cluster")} subtitle="Minimal registration flow">
          <ClusterForm onCreate={onCreate} disabled={!canOperate} t={t} />
          {installCommand && <InstallCommand command={installCommand} onCopy={onCopy} t={t} />}
        </Surface>
        <Surface title={t("Cluster topology")} subtitle={`${clusters.length} registered`}>
          <ClusterList clusters={clusters} selectedCluster={selectedCluster} onSelect={onSelect} onGenerateInstall={onGenerateInstall} onDelete={onDelete} onRotateToken={onRotateToken} canOperate={canOperate} currentUser={currentUser} t={t} />
        </Surface>
      </div>
      {selectedCluster && (
        <Surface title={selectedCluster.name} subtitle={`${selectedCluster.cluster_id} / ${selectedCluster.environment}`}>
          <ClusterDetail cluster={selectedCluster} detail={clusterDetail} onStartCollection={onStartCollection} canOperate={canOperate} t={t} />
        </Surface>
      )}
    </div>
  );
}

export function ClusterForm({ onCreate, disabled, t }) {
  const [form, setForm] = useState({
    name: "",
    environment: "dev",
    description: "",
    backend_url: window.location.origin,
  });
  const [busy, setBusy] = useState(false);
  async function submit(event) {
    event.preventDefault();
    setBusy(true);
    try {
      await onCreate(form);
      setForm({ ...form, name: "", description: "" });
    } finally {
      setBusy(false);
    }
  }
  return (
    <form className="cluster-form" onSubmit={submit}>
      <label>{t("Cluster name")}<input className="form-control" value={form.name} disabled={disabled} required onChange={(event) => setForm({ ...form, name: event.target.value })} /></label>
      <label>{t("Environment")}<select className="form-select" value={form.environment} disabled={disabled} onChange={(event) => setForm({ ...form, environment: event.target.value })}><option>dev</option><option>stage</option><option>prod</option><option>dr</option></select></label>
      <label className="wide">{t("Description")}<textarea className="form-control" rows={2} value={form.description} disabled={disabled} onChange={(event) => setForm({ ...form, description: event.target.value })} /></label>
      <label className="wide">{t("Backend URL")}<input className="form-control" value={form.backend_url} disabled={disabled} onChange={(event) => setForm({ ...form, backend_url: event.target.value })} /></label>
      <button className="btn btn-primary icon-button" disabled={disabled || busy || !form.name.trim()}>
        <Icon name="plus-circle" />
        <span>{busy ? "..." : t("Generate install command")}</span>
      </button>
    </form>
  );
}

export function ClusterList({ clusters, selectedCluster, onSelect, onGenerateInstall, onDelete, onRotateToken, canOperate, currentUser, t }) {
  if (!clusters.length) return <EmptyState message={t("No clusters registered.")} />;
  return (
    <div className="cluster-list">
      {clusters.map((cluster) => (
        <article key={cluster.cluster_id} className={`cluster-row ${selectedCluster?.cluster_id === cluster.cluster_id ? "selected" : ""}`}>
          <button type="button" className="cluster-main" onClick={() => onSelect(cluster)}>
            <div className="cluster-node-icon"><Icon name="hdd-network" /></div>
            <div>
              <strong>{cluster.name}</strong>
              <span>{cluster.cluster_id}</span>
            </div>
          </button>
          <div className="cluster-meta">
            <StatusBadge value={cluster.status} tone={cluster.status === "active" ? "green" : "amber"} t={t} />
            <span>{cluster.environment}</span>
          </div>
          <div className="row-actions">
            {canOperate && <button className="btn btn-sm btn-outline-secondary" onClick={() => onGenerateInstall(cluster.cluster_id, window.location.origin)}>{t("Install command")}</button>}
            {currentUser.role === "admin" && <button className="btn btn-sm btn-outline-secondary" onClick={() => onRotateToken(cluster)}><Icon name="arrow-repeat" /></button>}
            {currentUser.role === "admin" && <button className="btn btn-sm btn-outline-danger" onClick={() => onDelete(cluster)}><Icon name="trash" /></button>}
          </div>
        </article>
      ))}
    </div>
  );
}

export function InstallCommand({ command, onCopy, t }) {
  const commandText = (command.commands || []).join("\n");
  return (
    <div className="install-command">
      <div className="install-head">
        <strong>{t("Install command")}</strong>
        <button className="btn btn-sm btn-outline-secondary icon-button" onClick={() => onCopy(commandText)}>
          <Icon name="clipboard" /><span>{t("Copy")}</span>
        </button>
      </div>
      <pre>{commandText}</pre>
      {(command.notes || []).map((note) => <p key={note} className="note-line">{note}</p>)}
    </div>
  );
}

export function ClusterDetail({ cluster, detail, onStartCollection, canOperate, t }) {
  const [activeTab, setActiveTab] = useState("agents");
  const agents = detail?.agents || [];
  const evidence = detail?.evidence || [];
  const topology = detail?.topology || {};
  const entities = topology.entities || [];
  const fleet = summarizeAgentFleet(agents, [cluster]);
  const tabs = [
    { id: "agents", label: t("Agents"), icon: "hdd-network", count: agents.length },
    { id: "evidence", label: t("Evidence"), icon: "clipboard2-pulse", count: evidence.length },
    { id: "topology", label: t("Topology"), icon: "diagram-3", count: entities.length },
  ];
  return (
    <div className="cluster-detail-ops">
      <div className="section-toolbar">
        <div>
          <h3>{t("Agents")}</h3>
          <p className="text-muted mb-0">Node agent posture, evidence requests, and observed topology for this cluster.</p>
        </div>
        {canOperate && <button className="btn btn-sm btn-primary icon-button" onClick={() => onStartCollection(cluster)}><Icon name="collection" /><span>{t("Collect evidence")}</span></button>}
      </div>

      <AgentHealthSummary fleet={fleet} agents={agents} t={t} />

      <div className="cluster-detail-tabs" role="tablist" aria-label="Cluster detail sections">
        {tabs.map((tab) => (
          <button key={tab.id} type="button" className={activeTab === tab.id ? "active" : ""} onClick={() => setActiveTab(tab.id)}>
            <Icon name={tab.icon} />
            <span>{tab.label}</span>
            <strong>{tab.count}</strong>
          </button>
        ))}
      </div>

      <div className="cluster-detail-panel">
        {activeTab === "agents" && (
          <ResponsiveTable
            empty={t("No agents registered.")}
            columns={[t("Node"), t("Status"), t("Version"), t("Last heartbeat"), t("Risk reason")]}
            rows={agents.map((agent) => [
              agent.node_name,
              <StatusBadge value={agent.health_status || agent.status || agent.reported_status} tone={agentHealthTone(agent)} t={t} />,
              agent.agent_version || "n/a",
              relativeTime(agent.last_heartbeat_at),
              <span className="health-reason">{agentReason(agent)}</span>,
            ])}
          />
        )}
        {activeTab === "evidence" && (
          <div className="evidence-list">
            {evidence.length ? evidence.slice(0, 12).map((item) => (
              <article key={item.request_id} className="evidence-item">
                <strong>{item.alert_name}</strong>
                <span>{item.node_name}</span>
                <StatusBadge value={item.status} tone={item.status === "completed" ? "green" : item.status === "failed" ? "red" : "amber"} t={t} />
              </article>
            )) : <EmptyState message="No evidence requests." />}
          </div>
        )}
        {activeTab === "topology" && (
          <div className="topology-entities">
            {entities.slice(0, 24).map((entity) => (
              <span key={entity.id} className="entity-pill">{entity.kind}/{entity.name}</span>
            ))}
            {!entities.length && <span className="text-muted">Topology observation is not loaded yet.</span>}
          </div>
        )}
        </div>
    </div>
  );
}

export function AgentHealthSummary({ fleet, agents, t }) {
  const degradedAgents = agents.filter((agent) => !["healthy", "registered"].includes(agent.health_status || agent.status || agent.reported_status));
  return (
    <div className="agent-health-summary">
      <div className="agent-health-stat ok"><span>{t("Healthy agents")}</span><strong>{fleet.healthy}</strong></div>
      <div className="agent-health-stat warn"><span>Stale</span><strong>{fleet.stale}</strong></div>
      <div className="agent-health-stat warn"><span>Degraded</span><strong>{fleet.degraded}</strong></div>
      <div className="agent-health-stat danger"><span>Offline</span><strong>{fleet.offline}</strong></div>
      <div className="agent-health-reasons">
        {degradedAgents.length ? degradedAgents.slice(0, 4).map((agent) => (
          <span key={agent.agent_id || agent.node_name}>
            <Icon name="exclamation-circle" /> {agent.node_name}: {agentReason(agent)}
          </span>
        )) : <span><Icon name="check2-circle" /> All registered agents are reporting healthy posture.</span>}
      </div>
    </div>
  );
}
