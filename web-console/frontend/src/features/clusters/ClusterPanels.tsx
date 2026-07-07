import type { FormEvent } from "react";
import { useState } from "react";

import { EmptyState, Icon, ResponsiveTable, StatusBadge } from "../../components/common";
import { agentHealthTone, agentReason, relativeTime, summarizeAgentFleet } from "../../lib/consoleUtils";
import type {
  AgentHealthView,
  ClusterCreateForm,
  ClusterDetailState,
  ClusterView,
  EvidenceRequestView,
  InstallCommandView,
  JsonObject,
  JsonValue,
  TFunction,
  UserAccount,
} from "../../types";

type MaybePromise<T = void> = T | Promise<T>;

interface ClusterFormProps {
  onCreate: (form: ClusterCreateForm) => MaybePromise;
  disabled: boolean;
  t: TFunction;
}

interface ClusterListProps {
  clusters: ClusterView[];
  selectedCluster: ClusterView | null;
  onSelect: (cluster: ClusterView) => MaybePromise;
  onGenerateInstall: (clusterId: string, backendUrl?: string) => MaybePromise<unknown>;
  onDelete: (cluster: ClusterView) => void;
  onRotateToken: (cluster: ClusterView) => MaybePromise;
  canOperate: boolean;
  currentUser: UserAccount;
  t: TFunction;
}

interface InstallCommandProps {
  command: InstallCommandView;
  onCopy: (text: string) => MaybePromise;
  t: TFunction;
}

interface ClusterDetailProps {
  cluster: ClusterView;
  detail: ClusterDetailState | null;
  onStartCollection: (cluster: ClusterView) => MaybePromise;
  canOperate: boolean;
  t: TFunction;
}

interface AgentHealthSummaryProps {
  agents: AgentHealthView[];
  cluster: ClusterView;
  t: TFunction;
}

interface TopologyEntity {
  id: string;
  kind: string;
  name: string;
}

export function ClusterForm({ onCreate, disabled, t }: ClusterFormProps) {
  const [form, setForm] = useState<ClusterCreateForm>({
    name: "",
    environment: "dev",
    description: "",
    backend_url: window.location.origin,
  });
  const [busy, setBusy] = useState(false);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setBusy(true);
    try {
      await onCreate(form);
      setForm((current) => ({ ...current, name: "", description: "" }));
    } finally {
      setBusy(false);
    }
  }

  return (
    <form className="cluster-form" onSubmit={submit}>
      <label>
        {t("Cluster name")}
        <input
          className="form-control"
          value={form.name}
          disabled={disabled}
          required
          onChange={(event) => setForm({ ...form, name: event.target.value })}
        />
      </label>
      <label>
        {t("Environment")}
        <select
          className="form-select"
          value={form.environment}
          disabled={disabled}
          onChange={(event) => setForm({ ...form, environment: event.target.value })}
        >
          <option>dev</option>
          <option>stage</option>
          <option>prod</option>
          <option>dr</option>
        </select>
      </label>
      <label className="wide">
        {t("Description")}
        <textarea
          className="form-control"
          rows={2}
          value={form.description}
          disabled={disabled}
          onChange={(event) => setForm({ ...form, description: event.target.value })}
        />
      </label>
      <label className="wide">
        {t("Backend URL")}
        <input
          className="form-control"
          value={form.backend_url}
          disabled={disabled}
          onChange={(event) => setForm({ ...form, backend_url: event.target.value })}
        />
      </label>
      <button className="btn btn-primary icon-button" disabled={disabled || busy || !form.name.trim()}>
        <Icon name="plus-circle" />
        <span>{busy ? "..." : t("Generate install command")}</span>
      </button>
    </form>
  );
}

export function ClusterList({
  clusters,
  selectedCluster,
  onSelect,
  onGenerateInstall,
  onDelete,
  onRotateToken,
  canOperate,
  currentUser,
  t,
}: ClusterListProps) {
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
            {canOperate && (
              <button
                className="btn btn-sm btn-outline-secondary"
                onClick={() => onGenerateInstall(cluster.cluster_id, window.location.origin)}
              >
                {t("Install command")}
              </button>
            )}
            {currentUser.role === "admin" && (
              <button className="btn btn-sm btn-outline-secondary" onClick={() => onRotateToken(cluster)}>
                <Icon name="arrow-repeat" />
              </button>
            )}
            {currentUser.role === "admin" && (
              <button className="btn btn-sm btn-outline-danger" onClick={() => onDelete(cluster)}>
                <Icon name="trash" />
              </button>
            )}
          </div>
        </article>
      ))}
    </div>
  );
}

export function InstallCommand({ command, onCopy, t }: InstallCommandProps) {
  const commandText = (command.commands || []).join("\n");
  return (
    <div className="install-command">
      <div className="install-head">
        <strong>{t("Install command")}</strong>
        <button className="btn btn-sm btn-outline-secondary icon-button" onClick={() => onCopy(commandText)}>
          <Icon name="clipboard" />
          <span>{t("Copy")}</span>
        </button>
      </div>
      <pre>{commandText}</pre>
      {(command.notes || []).map((note) => <p key={note} className="note-line">{note}</p>)}
    </div>
  );
}

export function ClusterDetail({ cluster, detail, onStartCollection, canOperate, t }: ClusterDetailProps) {
  const [activeTab, setActiveTab] = useState("agents");
  const agents = detail?.agents || [];
  const evidence = detail?.evidence || [];
  const entities = topologyEntities(detail?.topology);
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
          <p className="text-muted mb-0">{t("Node agent posture, evidence requests, and observed topology for this cluster.")}</p>
        </div>
        {canOperate && (
          <button className="btn btn-sm btn-primary icon-button" onClick={() => onStartCollection(cluster)}>
            <Icon name="collection" />
            <span>{t("Collect evidence")}</span>
          </button>
        )}
      </div>

      <AgentHealthSummary agents={agents} cluster={cluster} t={t} />

      <div className="cluster-detail-tabs" role="tablist" aria-label={t("Cluster detail sections")}>
        {tabs.map((tab) => (
          <button key={tab.id} type="button" className={activeTab === tab.id ? "active" : ""} onClick={() => setActiveTab(tab.id)}>
            <Icon name={tab.icon} />
            <span>{tab.label}</span>
            <strong>{tab.count}</strong>
          </button>
        ))}
      </div>

      <div className="cluster-detail-panel">
        {activeTab === "agents" && <AgentTable agents={agents} t={t} />}
        {activeTab === "evidence" && <EvidenceList evidence={evidence} t={t} />}
        {activeTab === "topology" && <TopologyEntities entities={entities} t={t} />}
      </div>
    </div>
  );
}

function AgentTable({ agents, t }: { agents: AgentHealthView[]; t: TFunction }) {
  return (
    <ResponsiveTable
      empty={t("No agents registered.")}
      columns={[t("Node"), t("Status"), t("Version"), t("Last heartbeat"), t("Risk reason")]}
      rows={agents.map((agent) => [
        agent.node_name,
        <StatusBadge
          value={agent.health_status || agent.status || agent.reported_status}
          tone={agentHealthTone(agent)}
          t={t}
        />,
        agent.agent_version || "n/a",
        relativeTime(agent.last_heartbeat_at),
        <span className="health-reason">{agentReason(agent)}</span>,
      ])}
    />
  );
}

function EvidenceList({ evidence, t }: { evidence: EvidenceRequestView[]; t: TFunction }) {
  return (
    <div className="evidence-list">
      {evidence.length ? evidence.slice(0, 12).map((item) => (
        <article key={item.request_id} className="evidence-item">
          <strong>{item.alert_name}</strong>
          <span>{item.node_name}</span>
          <StatusBadge
            value={item.status}
            tone={item.status === "completed" ? "green" : item.status === "failed" ? "red" : "amber"}
            t={t}
          />
        </article>
      )) : <EmptyState message={t("No evidence requests.")} />}
    </div>
  );
}

function TopologyEntities({ entities, t }: { entities: TopologyEntity[]; t: TFunction }) {
  return (
    <div className="topology-entities">
      {entities.slice(0, 24).map((entity) => (
        <span key={entity.id} className="entity-pill">{entity.kind}/{entity.name}</span>
      ))}
      {!entities.length && <span className="text-muted">{t("Topology observation is not loaded yet.")}</span>}
    </div>
  );
}

export function AgentHealthSummary({ agents, cluster, t }: AgentHealthSummaryProps) {
  const fleet = summarizeAgentFleet(agents, [cluster]);
  const degradedAgents = agents.filter((agent) => !["healthy", "registered"].includes(agent.health_status || agent.status || agent.reported_status || ""));
  return (
    <div className="agent-health-summary">
      <div className="agent-health-stat ok"><span>{t("Healthy agents")}</span><strong>{fleet.healthy}</strong></div>
      <div className="agent-health-stat warn"><span>{t("Stale")}</span><strong>{fleet.stale}</strong></div>
      <div className="agent-health-stat warn"><span>{t("Degraded")}</span><strong>{fleet.degraded}</strong></div>
      <div className="agent-health-stat danger"><span>{t("Offline")}</span><strong>{fleet.offline}</strong></div>
      <div className="agent-health-reasons">
        {degradedAgents.length ? degradedAgents.slice(0, 4).map((agent) => (
          <span key={agent.agent_id || agent.node_name}>
            <Icon name="exclamation-circle" /> {agent.node_name}: {agentReason(agent)}
          </span>
        )) : <span><Icon name="check2-circle" /> {t("All registered agents are reporting healthy posture.")}</span>}
      </div>
    </div>
  );
}

function topologyEntities(topology: JsonObject | null | undefined): TopologyEntity[] {
  const raw = topology?.entities;
  if (!Array.isArray(raw)) return [];
  return raw
    .map((item, index) => topologyEntity(item, index))
    .filter((item): item is TopologyEntity => item !== null);
}

function topologyEntity(item: JsonValue, index: number): TopologyEntity | null {
  if (!item || typeof item !== "object" || Array.isArray(item)) return null;
  const record = item as JsonObject;
  const kind = stringValue(record.kind) || "Entity";
  const name = stringValue(record.name) || stringValue(record.id) || `entity-${index}`;
  return {
    id: stringValue(record.id) || `${kind}-${name}-${index}`,
    kind,
    name,
  };
}

function stringValue(value: JsonValue | undefined): string {
  return value === null || value === undefined ? "" : String(value);
}
