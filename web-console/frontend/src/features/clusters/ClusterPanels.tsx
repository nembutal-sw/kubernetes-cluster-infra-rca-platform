import type { FormEvent } from "react";
import { useEffect, useState } from "react";

import { EmptyState, Icon, ResponsiveTable, StatusBadge } from "../../components/common";
import { agentHealthTone, agentReason, normalizedAgentStatus, relativeTime, summarizeAgentFleet } from "../../lib/consoleUtils";
import type {
  AgentHealthView,
  AgentEnrollmentProfile,
  AgentEnrollmentUpdate,
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
  onUpdateEnrollment: (cluster: ClusterView, update: AgentEnrollmentUpdate) => MaybePromise;
  canOperate: boolean;
  canAdmin: boolean;
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
  onUpdateEnrollment,
  canOperate,
  canAdmin,
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
    {
      id: "enrollment",
      label: t("Agent enrollment"),
      icon: "shield-lock",
      count: detail?.enrollment?.mode === "kubernetes_token_review" ? 1 : 0,
    },
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
        {activeTab === "enrollment" && (
          <AgentEnrollmentSettings
            cluster={cluster}
            profile={detail?.enrollment}
            canAdmin={canAdmin}
            onUpdate={onUpdateEnrollment}
            t={t}
          />
        )}
      </div>
    </div>
  );
}

function AgentEnrollmentSettings({
  cluster,
  profile,
  canAdmin,
  onUpdate,
  t,
}: {
  cluster: ClusterView;
  profile?: AgentEnrollmentProfile | null;
  canAdmin: boolean;
  onUpdate: (cluster: ClusterView, update: AgentEnrollmentUpdate) => MaybePromise;
  t: TFunction;
}) {
  const [mode, setMode] = useState<AgentEnrollmentUpdate["mode"]>(profile?.mode || "bootstrap_token");
  const [apiServerUrl, setApiServerUrl] = useState(profile?.api_server_url || "");
  const [audience, setAudience] = useState(
    profile?.audience || "cluster-infra-rca-agent-enrollment",
  );
  const [namespace, setNamespace] = useState(profile?.namespace || "rca-system");
  const [serviceAccount, setServiceAccount] = useState(profile?.service_account || "cluster-infra-rca-agent");
  const [reviewerTokenPath, setReviewerTokenPath] = useState(
    profile?.reviewer_token_path || "/var/run/secrets/kubernetes.io/serviceaccount/token",
  );
  const [expectedServiceAccountUid, setExpectedServiceAccountUid] = useState(profile?.expected_service_account_uid || "");
  const [expectedDaemonSetName, setExpectedDaemonSetName] = useState(profile?.expected_daemon_set_name || "cluster-infra-rca-agent");
  const [expectedDaemonSetUid, setExpectedDaemonSetUid] = useState(profile?.expected_daemon_set_uid || "");
  const [allowedImageDigest, setAllowedImageDigest] = useState(profile?.allowed_image_digest || "");
  const [requiredLabels, setRequiredLabels] = useState(JSON.stringify(
    profile?.required_pod_labels || {
      "app.kubernetes.io/name": "cluster-infra-rca-agent",
      "cluster-infra-rca.io/cluster-id": cluster.cluster_id,
    },
    null,
    2,
  ));
  const [caBundlePem, setCaBundlePem] = useState("");
  const [fallbackAllowed, setFallbackAllowed] = useState(profile?.bootstrap_fallback_allowed ?? true);
  const [legacyGraceUntil, setLegacyGraceUntil] = useState(
    datetimeLocalValue(profile?.legacy_unbound_token_grace_until),
  );
  const [validationError, setValidationError] = useState("");
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    setMode(profile?.mode || "bootstrap_token");
    setApiServerUrl(profile?.api_server_url || "");
    setAudience(profile?.audience || "cluster-infra-rca-agent-enrollment");
    setNamespace(profile?.namespace || "rca-system");
    setServiceAccount(profile?.service_account || "cluster-infra-rca-agent");
    setReviewerTokenPath(profile?.reviewer_token_path || "/var/run/secrets/kubernetes.io/serviceaccount/token");
    setExpectedServiceAccountUid(profile?.expected_service_account_uid || "");
    setExpectedDaemonSetName(profile?.expected_daemon_set_name || "cluster-infra-rca-agent");
    setExpectedDaemonSetUid(profile?.expected_daemon_set_uid || "");
    setAllowedImageDigest(profile?.allowed_image_digest || "");
    setRequiredLabels(JSON.stringify(
      profile?.required_pod_labels || {
        "app.kubernetes.io/name": "cluster-infra-rca-agent",
        "cluster-infra-rca.io/cluster-id": cluster.cluster_id,
      },
      null,
      2,
    ));
    setFallbackAllowed(profile?.bootstrap_fallback_allowed ?? true);
    setLegacyGraceUntil(datetimeLocalValue(profile?.legacy_unbound_token_grace_until));
    setCaBundlePem("");
    setValidationError("");
  }, [cluster.cluster_id, profile?.updated_at, profile?.mode]);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const enablingStrictMode = mode === "kubernetes_token_review"
      && !fallbackAllowed
      && !(profile?.mode === "kubernetes_token_review" && !profile.bootstrap_fallback_allowed);
    if (enablingStrictMode && !window.confirm(t("Disable bootstrap fallback and revoke the current bootstrap token?"))) {
      return;
    }
    setBusy(true);
    try {
      let parsedLabels: Record<string, string> | undefined;
      if (mode === "kubernetes_token_review") {
        const parsed = JSON.parse(requiredLabels) as unknown;
        if (!parsed || Array.isArray(parsed) || typeof parsed !== "object"
          || Object.values(parsed).some((value) => typeof value !== "string")) {
          throw new Error(t("Required Pod labels must be a JSON object with string values."));
        }
        parsedLabels = parsed as Record<string, string>;
      }
      setValidationError("");
      await onUpdate(cluster, mode === "bootstrap_token" ? { mode } : {
        mode,
        api_server_url: apiServerUrl.trim(),
        ca_bundle_pem: caBundlePem.trim() || undefined,
        audience: audience.trim(),
        namespace: namespace.trim(),
        service_account: serviceAccount.trim(),
        reviewer_token_path: reviewerTokenPath.trim(),
        expected_service_account_uid: expectedServiceAccountUid.trim(),
        expected_daemon_set_name: expectedDaemonSetName.trim(),
        expected_daemon_set_uid: expectedDaemonSetUid.trim(),
        required_pod_labels: parsedLabels,
        allowed_image_digest: allowedImageDigest.trim(),
        legacy_unbound_token_grace_until: legacyGraceUntil
          ? new Date(legacyGraceUntil).toISOString()
          : null,
        bootstrap_fallback_allowed: fallbackAllowed,
      });
      setCaBundlePem("");
    } catch (error) {
      setValidationError(error instanceof Error ? error.message : t("Enrollment validation failed."));
    } finally {
      setBusy(false);
    }
  }

  const strict = profile?.mode === "kubernetes_token_review" && !profile.bootstrap_fallback_allowed;
  return (
    <form className="agent-enrollment-settings" onSubmit={submit}>
      <div className="enrollment-status-grid">
        <div><span>{t("Mode")}</span><strong>{enrollmentModeLabel(profile?.mode, t)}</strong></div>
        <div><span>{t("Service account")}</span><strong>{profile?.service_account || "n/a"}</strong></div>
        <div><span>{t("Audience")}</span><strong>{profile?.audience || "n/a"}</strong></div>
        <div><span>{t("CA fingerprint")}</span><strong className="fingerprint">{profile?.ca_sha256 || "n/a"}</strong></div>
        <div><span>{t("Profile version")}</span><strong>{profile?.profile_version || "n/a"}</strong></div>
        <div><span>{t("Workload identity")}</span><strong>{profile?.workload_identity_ready ? t("Ready") : t("Binding required")}</strong></div>
        <div><span>{t("Legacy unbound agents")}</span><strong>{profile?.legacy_unbound_agents?.length || 0}</strong></div>
        <div><span>{t("Legacy grace expires")}</span><strong>{profile?.legacy_unbound_token_grace_until ? relativeTime(profile.legacy_unbound_token_grace_until) : t("Disabled")}</strong></div>
      </div>
      {mode === "kubernetes_token_review" && !profile?.workload_identity_ready && (
        <div className="enrollment-token-status">
          <Icon name="shield-lock" />
          <strong>{t("Agent registration stays blocked until all workload identity fields are bound.")}</strong>
        </div>
      )}
      {strict && (
        <div className="enrollment-strict-status">
          <Icon name="shield-lock" />
          <strong>{t("Bootstrap fallback disabled")}</strong>
        </div>
      )}
      {profile?.bootstrap_token_rotation_required && (
        <div className="enrollment-token-status">
          <Icon name="arrow-repeat" />
          <strong>{t("Bootstrap token rotation required")}</strong>
        </div>
      )}
      {!!profile?.legacy_unbound_agents?.length && (
        <div className="enrollment-legacy-agents">
          <div className="enrollment-token-status">
            <Icon name="exclamation-triangle" />
            <strong>{t("These agents must re-register before the cluster grace expires.")}</strong>
          </div>
          <ResponsiveTable
            empty={t("No legacy unbound agents.")}
            columns={[t("Node"), t("Status"), t("Last heartbeat"), t("Token state")]}
            rows={profile.legacy_unbound_agents.map((agent) => [
              agent.node_name,
              <StatusBadge value={agent.status} tone={agent.token_revoked ? "red" : "amber"} t={t} />,
              relativeTime(agent.last_heartbeat_at),
              agent.token_revoked ? t("Revoked") : t("Active"),
            ])}
          />
        </div>
      )}
      {canAdmin && (
        <div className="enrollment-editor">
          <label>
            {t("Enrollment mode")}
            <select className="form-select" value={mode} disabled={busy} onChange={(event) => setMode(event.target.value as AgentEnrollmentUpdate["mode"])}>
              <option value="bootstrap_token">{t("Bootstrap token")}</option>
              <option value="kubernetes_token_review">{t("Kubernetes TokenReview")}</option>
            </select>
          </label>
          {mode === "kubernetes_token_review" && (
            <>
              <label className="wide">{t("API Server URL")}<input className="form-control" type="url" required value={apiServerUrl} disabled={busy} onChange={(event) => setApiServerUrl(event.target.value)} /></label>
              <label>{t("Dedicated enrollment audience")}<input className="form-control" required value={audience} disabled={busy} onChange={(event) => setAudience(event.target.value)} /></label>
              <label>{t("Namespace")}<input className="form-control" required value={namespace} disabled={busy} onChange={(event) => setNamespace(event.target.value)} /></label>
              <label>{t("Service account")}<input className="form-control" required value={serviceAccount} disabled={busy} onChange={(event) => setServiceAccount(event.target.value)} /></label>
              <label className="wide">{t("Backend reviewer token path")}<input className="form-control font-monospace" required value={reviewerTokenPath} disabled={busy} onChange={(event) => setReviewerTokenPath(event.target.value)} /></label>
              <label>{t("Expected ServiceAccount UID")}<input className="form-control font-monospace" value={expectedServiceAccountUid} disabled={busy} onChange={(event) => setExpectedServiceAccountUid(event.target.value)} /></label>
              <label>{t("Expected DaemonSet name")}<input className="form-control font-monospace" value={expectedDaemonSetName} disabled={busy} onChange={(event) => setExpectedDaemonSetName(event.target.value)} /></label>
              <label>{t("Expected DaemonSet UID")}<input className="form-control font-monospace" value={expectedDaemonSetUid} disabled={busy} onChange={(event) => setExpectedDaemonSetUid(event.target.value)} /></label>
              <label>{t("Allowed Agent image digest")}<input className="form-control font-monospace" value={allowedImageDigest} disabled={busy} placeholder="sha256:..." onChange={(event) => setAllowedImageDigest(event.target.value)} /></label>
              <label>
                {t("Legacy grace expires")}
                <input
                  className="form-control"
                  type="datetime-local"
                  value={legacyGraceUntil}
                  disabled={busy}
                  onChange={(event) => setLegacyGraceUntil(event.target.value)}
                />
              </label>
              <label className="wide">
                {t("Required Pod labels")}
                <textarea className="form-control enrollment-ca" rows={5} required value={requiredLabels} disabled={busy} onChange={(event) => setRequiredLabels(event.target.value)} />
              </label>
              <label className="wide">
                {t("CA bundle PEM")}
                <textarea className="form-control enrollment-ca" rows={5} required={!profile?.configured} value={caBundlePem} disabled={busy} placeholder={profile?.configured ? t("Leave blank to keep the current CA bundle") : "-----BEGIN CERTIFICATE-----"} onChange={(event) => setCaBundlePem(event.target.value)} />
              </label>
              <label className="form-check enrollment-fallback">
                <input className="form-check-input" type="checkbox" checked={fallbackAllowed} disabled={busy} onChange={(event) => setFallbackAllowed(event.target.checked)} />
                <span className="form-check-label">{t("Allow bootstrap fallback")}</span>
              </label>
            </>
          )}
          {validationError && <div className="wide alert alert-danger mb-0">{validationError}</div>}
          <div className="wide enrollment-actions">
            <button className="btn btn-primary icon-button" disabled={busy}>
              <Icon name="save" /><span>{busy ? "..." : t("Save enrollment")}</span>
            </button>
          </div>
        </div>
      )}
    </form>
  );
}

function enrollmentModeLabel(mode: AgentEnrollmentProfile["mode"] | undefined, t: TFunction): string {
  return mode === "kubernetes_token_review" ? t("Kubernetes TokenReview") : t("Bootstrap token");
}

function datetimeLocalValue(value?: string | null): string {
  if (!value) {
    return "";
  }
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return "";
  }
  const local = new Date(date.getTime() - date.getTimezoneOffset() * 60_000);
  return local.toISOString().slice(0, 16);
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
