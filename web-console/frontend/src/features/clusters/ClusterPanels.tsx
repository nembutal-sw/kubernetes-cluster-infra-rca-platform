import type { FormEvent } from "react";
import { useEffect, useState } from "react";

import { EmptyState, Icon, ResponsiveTable, StatusBadge } from "../../components/common";
import { agentHealthTone, agentReason, normalizedAgentStatus, relativeTime, summarizeAgentFleet } from "../../lib/consoleUtils";
import type {
  AgentHealthView,
  ClusterCreateForm,
  ClusterDetailState,
  ClusterThresholdSettings,
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
  onUpdateThresholds: (cluster: ClusterView, thresholds: Record<string, number>, reason: string) => MaybePromise;
  onClearThresholds: (cluster: ClusterView) => MaybePromise;
  canOperate: boolean;
  t: TFunction;
}

interface AgentHealthSummaryProps {
  agents: AgentHealthView[];
  cluster: ClusterView;
  t: TFunction;
}

interface AgentFleetPanelProps {
  agents: AgentHealthView[];
  clusters: ClusterView[];
  onOpenCluster: (cluster: ClusterView) => MaybePromise;
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
          data-testid="cluster-name"
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
          data-testid="cluster-environment"
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
          data-testid="cluster-description"
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
          data-testid="cluster-backend-url"
          value={form.backend_url}
          disabled={disabled}
          onChange={(event) => setForm({ ...form, backend_url: event.target.value })}
        />
      </label>
      <button className="btn btn-primary icon-button" data-testid="cluster-create" disabled={disabled || busy || !form.name.trim()}>
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
        <article key={cluster.cluster_id} data-testid="cluster-row" data-cluster-id={cluster.cluster_id} className={`cluster-row ${selectedCluster?.cluster_id === cluster.cluster_id ? "selected" : ""}`}>
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
                data-testid="cluster-install-command"
                onClick={() => onGenerateInstall(cluster.cluster_id, window.location.origin)}
              >
                {t("Install command")}
              </button>
            )}
            {currentUser.role === "admin" && (
              <button className="btn btn-sm btn-outline-secondary" aria-label={t("Rotate agent token")} title={t("Rotate agent token")} onClick={() => onRotateToken(cluster)}>
                <Icon name="arrow-repeat" />
              </button>
            )}
            {currentUser.role === "admin" && (
              <button className="btn btn-sm btn-outline-danger" data-testid="cluster-delete" aria-label={t("Delete cluster")} title={t("Delete cluster")} onClick={() => onDelete(cluster)}>
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
    <div className="install-command" data-testid="install-command">
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

export function ClusterDetail({
  cluster,
  detail,
  onStartCollection,
  onUpdateThresholds,
  onClearThresholds,
  canOperate,
  t,
}: ClusterDetailProps) {
  const [activeTab, setActiveTab] = useState("agents");
  const agents = detail?.agents || [];
  const evidence = detail?.evidence || [];
  const entities = topologyEntities(detail?.topology);
  const thresholdOverrides = Object.keys(detail?.thresholds?.overrides || {}).length;
  const tabs = [
    { id: "agents", label: t("Agents"), icon: "hdd-network", count: agents.length },
    { id: "evidence", label: t("Evidence"), icon: "clipboard2-pulse", count: evidence.length },
    { id: "topology", label: t("Topology"), icon: "diagram-3", count: entities.length },
    { id: "thresholds", label: t("Thresholds"), icon: "sliders", count: thresholdOverrides },
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
        {activeTab === "thresholds" && (
          <ThresholdSettings
            cluster={cluster}
            settings={detail?.thresholds}
            canOperate={canOperate}
            onUpdate={onUpdateThresholds}
            onClear={onClearThresholds}
            t={t}
          />
        )}
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

function ThresholdSettings({
  cluster,
  settings,
  canOperate,
  onUpdate,
  onClear,
  t,
}: {
  cluster: ClusterView;
  settings?: ClusterThresholdSettings | null;
  canOperate: boolean;
  onUpdate: (cluster: ClusterView, thresholds: Record<string, number>, reason: string) => MaybePromise;
  onClear: (cluster: ClusterView) => MaybePromise;
  t: TFunction;
}) {
  const effective = settings?.effective || {};
  const defaults = settings?.defaults || {};
  const overrides = settings?.overrides || {};
  const supportedKeys = settings?.supported_keys?.length ? settings.supported_keys : Object.keys(effective).sort();
  const [draft, setDraft] = useState<Record<string, string>>({});
  const [reason, setReason] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    const next: Record<string, string> = {};
    Object.entries(overrides).forEach(([key, value]) => {
      next[key] = formatThreshold(value);
    });
    setDraft(next);
    setReason("");
    setError("");
  }, [settings?.updated_at, JSON.stringify(overrides)]);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const parsed = parseThresholdDraft(draft, settings, defaults);
    if (parsed.error) {
      setError(t(parsed.error));
      return;
    }
    setBusy(true);
    setError("");
    try {
      await onUpdate(cluster, parsed.values, reason.trim());
    } finally {
      setBusy(false);
    }
  }

  async function clearOverrides() {
    if (!window.confirm(t("Clear all threshold overrides for this cluster?"))) {
      return;
    }
    setBusy(true);
    setError("");
    try {
      await onClear(cluster);
    } finally {
      setBusy(false);
    }
  }

  const rows = supportedKeys.map((key) => {
    const definition = definitionFor(settings, key);
    const overridden = Object.prototype.hasOwnProperty.call(overrides, key);
    const draftValue = draft[key] ?? "";
    return [
      <div className="threshold-name">
        <span className="threshold-key">{key}</span>
        <small>{definition?.label || key}</small>
      </div>,
      formatThreshold(defaults[key]),
      <input
        className="form-control form-control-sm threshold-input"
        type="number"
        inputMode="decimal"
        min={definition?.minimum ?? 0}
        max={definition?.maximum ?? undefined}
        step="any"
        placeholder={formatThreshold(defaults[key])}
        value={draftValue}
        disabled={!canOperate || busy}
        onChange={(event) => setDraft({ ...draft, [key]: event.target.value })}
        aria-label={`${key} ${t("Override")}`}
      />,
      <strong className={overridden ? "threshold-override" : ""}>{formatThreshold(effective[key])}</strong>,
      <span className="threshold-unit">{definition?.unit || "value"}</span>,
      overridden ? <StatusBadge value={t("Overridden")} tone="amber" t={t} /> : <span className="text-muted">{t("Default")}</span>,
    ];
  });

  return (
    <form className="threshold-settings" onSubmit={submit}>
      <div className="threshold-summary">
        <span>{t("Cluster threshold overrides")}</span>
        <strong>{Object.keys(overrides).length}</strong>
        {settings?.updated_at && <em>{t("Updated")} {relativeTime(settings.updated_at)}</em>}
      </div>
      <div className="threshold-editor-note">
        <Icon name="sliders" />
        <span>{t("Leave a value blank to use the platform default. Only filled values are stored as cluster overrides.")}</span>
      </div>
      {error && <div className="form-error">{error}</div>}
      <ResponsiveTable
        empty={t("No threshold settings loaded.")}
        columns={[t("Key"), t("Default"), t("Override"), t("Effective"), t("Unit"), t("Source")]}
        rows={rows}
      />
      {canOperate && (
        <div className="threshold-actions">
          <label>
            {t("Change reason")}
            <input
              className="form-control"
              value={reason}
              maxLength={500}
              disabled={busy}
              onChange={(event) => setReason(event.target.value)}
              placeholder={t("Optional operating note")}
            />
          </label>
          <div>
            <button className="btn btn-primary icon-button" disabled={busy}>
              <Icon name="save" />
              <span>{busy ? "..." : t("Save overrides")}</span>
            </button>
            <button type="button" className="btn btn-outline-secondary icon-button" disabled={busy || !Object.keys(overrides).length} onClick={clearOverrides}>
              <Icon name="x-circle" />
              <span>{t("Clear overrides")}</span>
            </button>
          </div>
        </div>
      )}
    </form>
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

const AGENT_STATUS_FILTERS = [
  { key: "all", label: "All" },
  { key: "healthy", label: "Healthy" },
  { key: "stale", label: "Stale" },
  { key: "collector_degraded", label: "Collector degraded" },
  { key: "version_mismatch", label: "Version mismatch" },
  { key: "unauthorized", label: "Unauthorized" },
  { key: "offline", label: "Offline" },
] as const;

export function AgentFleetPanel({ agents, clusters, onOpenCluster, t }: AgentFleetPanelProps) {
  const [statusFilter, setStatusFilter] = useState<(typeof AGENT_STATUS_FILTERS)[number]["key"]>("all");
  const [query, setQuery] = useState("");
  const clusterById = new Map(clusters.map((cluster) => [cluster.cluster_id, cluster]));
  const normalizedQuery = query.trim().toLowerCase();
  const filteredAgents = agents.filter((agent) => {
    const status = normalizedAgentStatus(agent);
    const statusMatches = statusFilter === "all" || agentStatusMatches(status, statusFilter);
    if (!statusMatches) return false;
    if (!normalizedQuery) return true;
    const cluster = agent.cluster_id ? clusterById.get(agent.cluster_id) : undefined;
    return [agent.node_name, agent.cluster_id, cluster?.name, agent.agent_version, agentReason(agent)]
      .some((value) => String(value || "").toLowerCase().includes(normalizedQuery));
  });

  return (
    <div className="agent-fleet-panel" data-testid="agent-fleet-panel">
      <div className="agent-fleet-toolbar">
        <div className="agent-fleet-filters" role="group" aria-label={t("Agent status filters")}>
          {AGENT_STATUS_FILTERS.map((filter) => {
            const count = filter.key === "all"
              ? agents.length
              : agents.filter((agent) => agentStatusMatches(normalizedAgentStatus(agent), filter.key)).length;
            return (
              <button
                key={filter.key}
                type="button"
                data-testid={`agent-status-filter-${filter.key}`}
                className={statusFilter === filter.key ? "active" : ""}
                aria-pressed={statusFilter === filter.key}
                onClick={() => setStatusFilter(filter.key)}
              >
                <span>{t(filter.label)}</span>
                <strong>{count}</strong>
              </button>
            );
          })}
        </div>
        <label className="agent-fleet-search">
          <Icon name="search" />
          <span className="visually-hidden">{t("Search agents")}</span>
          <input
            className="form-control form-control-sm"
            data-testid="agent-fleet-search"
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            placeholder={t("Search node, cluster, version, or reason")}
          />
        </label>
      </div>

      {!agents.length && <EmptyState message={t("No agents registered.")} />}
      {agents.length > 0 && !filteredAgents.length && <EmptyState message={t("No agents match the current filters.")} />}
      {filteredAgents.length > 0 && (
        <div className="agent-fleet-list">
          {filteredAgents.map((agent) => {
            const cluster = agent.cluster_id ? clusterById.get(agent.cluster_id) : undefined;
            const status = normalizedAgentStatus(agent);
            const collectors = agent.supported_collectors || [];
            return (
              <article key={agent.agent_id || `${agent.cluster_id}-${agent.node_name}`} data-testid="agent-fleet-row" className={`agent-fleet-row ${agentHealthTone(agent)}`}>
                <div className="agent-fleet-node">
                  <Icon name="server" />
                  <div>
                    <strong>{agent.node_name}</strong>
                    {cluster ? (
                      <button type="button" onClick={() => onOpenCluster(cluster)}>{cluster.name}</button>
                    ) : <span>{agent.cluster_id || t("Unknown cluster")}</span>}
                  </div>
                </div>
                <div className="agent-fleet-cell">
                  <span>{t("Status")}</span>
                  <StatusBadge value={agentStatusLabel(status)} tone={agentHealthTone(agent)} t={t} />
                </div>
                <div className="agent-fleet-cell">
                  <span>{t("Heartbeat age")}</span>
                  <strong>{formatHeartbeatAge(agent)}</strong>
                </div>
                <div className="agent-fleet-cell">
                  <span>{t("Version / protocol")}</span>
                  <strong>{agent.agent_version || "n/a"} · {agent.agent_protocol_version || "n/a"} / {agent.platform_protocol_version || "n/a"}</strong>
                </div>
                <div className="agent-fleet-cell">
                  <span>{t("Collectors")}</span>
                  <strong>{collectors.length}</strong>
                </div>
                <div className="agent-fleet-reason">
                  <span>{t("Risk reason")}</span>
                  <strong>{agentReason(agent)}</strong>
                </div>
              </article>
            );
          })}
        </div>
      )}
    </div>
  );
}

function agentStatusMatches(status: string, filter: (typeof AGENT_STATUS_FILTERS)[number]["key"]): boolean {
  if (filter === "all") return true;
  if (filter === "healthy") return ["healthy", "registered"].includes(status);
  if (filter === "collector_degraded") return ["collector_degraded", "degraded"].includes(status);
  return status === filter;
}

function agentStatusLabel(status: string): string {
  const labels: Record<string, string> = {
    healthy: "Healthy",
    registered: "Healthy",
    stale: "Stale",
    collector_degraded: "Collector degraded",
    degraded: "Collector degraded",
    version_mismatch: "Version mismatch",
    unauthorized: "Unauthorized",
    offline: "Offline",
  };
  return labels[status] || "Unknown";
}

function formatHeartbeatAge(agent: AgentHealthView): string {
  const seconds = Number(agent.heartbeat_age_seconds);
  if (Number.isFinite(seconds) && seconds >= 0) {
    if (seconds < 60) return `${Math.round(seconds)}s`;
    if (seconds < 3600) return `${Math.round(seconds / 60)}m`;
    return `${Math.round(seconds / 3600)}h`;
  }
  return relativeTime(agent.last_heartbeat_at);
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

function definitionFor(
  settings: ClusterThresholdSettings | null | undefined,
  key: string,
): NonNullable<ClusterThresholdSettings["definitions"]>[number] | undefined {
  return settings?.definitions?.find((definition) => definition.key === key);
}

function parseThresholdDraft(
  draft: Record<string, string>,
  settings: ClusterThresholdSettings | null | undefined,
  defaults: Record<string, number>,
): { values: Record<string, number>; error?: string } {
  const values: Record<string, number> = {};
  const keys = settings?.supported_keys?.length ? settings.supported_keys : Object.keys(defaults);
  for (const key of keys) {
    const raw = (draft[key] || "").trim();
    if (!raw) continue;
    const value = Number(raw);
    const definition = definitionFor(settings, key);
    const minimum = definition?.minimum ?? 0;
    const maximum = definition?.maximum;
    if (!Number.isFinite(value)) {
      return { values, error: `${key} must be a finite number.` };
    }
    if (value <= minimum) {
      return { values, error: `${key} must be greater than ${minimum}.` };
    }
    if (typeof maximum === "number" && value > maximum) {
      return { values, error: `${key} must be less than or equal to ${maximum}.` };
    }
    values[key] = value;
  }
  const effective = { ...defaults, ...values };
  const orderedPairs = [
    ["disk.warning.percent", "disk.critical.percent"],
    ["inode.warning.percent", "inode.critical.percent"],
    ["pid.warning.percent", "pid.critical.percent"],
    ["conntrack.warning.percent", "conntrack.critical.percent"],
  ];
  for (const [warningKey, criticalKey] of orderedPairs) {
    const warning = effective[warningKey];
    const critical = effective[criticalKey];
    if (typeof warning === "number" && typeof critical === "number" && critical < warning) {
      return { values, error: `${criticalKey} must be greater than or equal to ${warningKey}.` };
    }
  }
  return { values };
}

function formatThreshold(value: number | undefined): string {
  return typeof value === "number" && Number.isFinite(value) ? String(value) : "n/a";
}
