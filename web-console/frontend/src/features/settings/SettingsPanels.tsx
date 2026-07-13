import type { FormEvent, ReactNode } from "react";
import { useEffect, useState } from "react";

import {
  EmptyState,
  Icon,
  LanguageSwitch,
  StatusBadge,
  Surface,
} from "../../components/common";
import type { Locale } from "../../constants";
import { formatDate, platformInfoRows, runConsoleLayoutAudit } from "../../lib/consoleUtils";
import type {
  AuditEventView,
  CatalogDiffEntry,
  CatalogOverrideDraft,
  CatalogOverrideHandoff,
  CatalogOverridePreviewResponse,
  GitOpsChange,
  GitOpsDeploymentState,
  LlmConfigurationInfo,
  LlmDiagnosticCheck,
  LlmDiagnosticResponse,
  LlmProviderSetupOption,
  LlmSetupGuideResponse,
  LlmTestResponse,
  LoginIdChangeForm,
  NotificationConfigurationInfo,
  NotificationTestResponse,
  OperationalCatalogDetail,
  PasswordChangeForm,
  PlatformInfo,
  TFunction,
  UserAccount,
} from "../../types";

type MaybePromise<T = void> = T | Promise<T>;

interface CredentialWarningProps {
  currentUser: UserAccount | null;
  t: TFunction;
}

interface PreferenceAndCredentialPanelsProps {
  locale: Locale;
  setLocale: (locale: Locale) => void;
  currentUser: UserAccount | null;
  onChangeLoginId: (form: LoginIdChangeForm) => MaybePromise;
  onChangePassword: (form: PasswordChangeForm) => MaybePromise;
  t: TFunction;
}

interface LayoutDiagnosticsSectionProps {
  t: TFunction;
}

interface LlmConfigurationSectionProps {
  platformInfo: PlatformInfo | null;
  llmDiagnostics: LlmDiagnosticResponse | null;
  llmSetupGuide: LlmSetupGuideResponse | null;
  currentUser: UserAccount | null;
  onTestLlm: () => LlmTestResponse | Promise<LlmTestResponse>;
  t: TFunction;
}

interface NotificationDeliverySectionProps {
  platformInfo: PlatformInfo | null;
  notificationHistory: AuditEventView[];
  currentUser: UserAccount | null;
  onTestNotification: () => NotificationTestResponse | Promise<NotificationTestResponse>;
  t: TFunction;
}

interface OperationsRuntimeSectionProps {
  platformInfo: PlatformInfo | null;
  t: TFunction;
}

interface CatalogSectionProps {
  catalogDetail: OperationalCatalogDetail | null;
  platformInfo: PlatformInfo | null;
  catalogOverrideDrafts: CatalogOverrideDraft[];
  currentUser: UserAccount | null;
  onPreviewCatalogOverride: (overrideJson: string, reason: string) => Promise<CatalogOverridePreviewResponse>;
  onCreateCatalogOverrideDraft: (overrideJson: string, reason: string) => Promise<CatalogOverrideDraft>;
  onDecideCatalogOverrideDraft: (
    draft: CatalogOverrideDraft,
    decision: "approve" | "reject" | "discard",
    note: string,
  ) => Promise<CatalogOverrideDraft>;
  onLoadCatalogOverrideHandoff: (draft: CatalogOverrideDraft) => Promise<CatalogOverrideHandoff>;
  onCreateCatalogGitOpsChange: (draft: CatalogOverrideDraft) => Promise<GitOpsChange>;
  onLoadCatalogGitOpsChanges: (draft: CatalogOverrideDraft) => Promise<GitOpsChange[]>;
  onUpdateGitOpsOutcome: (
    change: GitOpsChange,
    state: GitOpsDeploymentState,
    verificationResult: string,
    rollbackReference: string,
  ) => Promise<GitOpsChange>;
  t: TFunction;
}

interface PlatformInfoSectionProps {
  platformInfo: PlatformInfo | null;
  t: TFunction;
}

interface LayoutIssue {
  selector: string;
  text?: string;
  reason?: string;
}

interface LayoutAudit {
  viewport_width: number;
  viewport_height: number;
  page_overflow_x: boolean;
  offscreen: LayoutIssue[];
  overflowed: LayoutIssue[];
  clipped: LayoutIssue[];
  checked_at?: string;
}

interface LayoutAuditPanelProps {
  audit: LayoutAudit | null;
  t: TFunction;
}

interface DiagnosticGroupProps {
  title: string;
  items: LayoutIssue[];
}

interface InfoRow {
  key: string;
  label: ReactNode;
  value: ReactNode;
  tone?: string;
}

const DEFAULT_CATALOG_OVERRIDE_JSON = `{
  "schema_version": "rca-catalog/v1",
  "version": "preview-local",
  "rules": {
    "disk-pressure": {
      "enabled": false
    }
  }
}`;

export function CredentialWarning({ currentUser, t }: CredentialWarningProps) {
  if (currentUser?.email !== "admin") return null;
  return (
    <div className="credential-warning">
      <Icon name="shield-lock" />
      <div>
        <strong>{t("Default admin account is active")}</strong>
        <span>{t("Change the login ID and password after the first sign-in.")}</span>
      </div>
    </div>
  );
}

export function PreferenceAndCredentialPanels({
  locale,
  setLocale,
  currentUser,
  onChangeLoginId,
  onChangePassword,
  t,
}: PreferenceAndCredentialPanelsProps) {
  const [loginId, setLoginId] = useState<LoginIdChangeForm>({
    current_password: "",
    new_username: currentUser?.email || "",
  });
  const [password, setPassword] = useState<PasswordChangeForm>({ current_password: "", new_password: "" });

  useEffect(() => {
    setLoginId((current) => ({ ...current, new_username: currentUser?.email || "" }));
  }, [currentUser?.email]);

  async function submitLoginId(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    await onChangeLoginId(loginId);
    setLoginId((current) => ({ ...current, current_password: "" }));
  }

  async function submitPassword(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    await onChangePassword(password);
    setPassword({ current_password: "", new_password: "" });
  }

  return (
    <div className="split-grid">
      <Surface title={t("Language")} subtitle={t("Preference is stored in this browser")}>
        <LanguageSwitch locale={locale} setLocale={setLocale} expanded />
      </Surface>
      <Surface title={t("Change login ID")} subtitle={t("Use this after the first default admin sign-in")}>
        <form className="credential-form" onSubmit={submitLoginId}>
          <label>{t("Current login ID")}<input className="form-control" value={currentUser?.email || ""} readOnly /></label>
          <label>{t("New login ID")}<input className="form-control" autoComplete="username" minLength={3} maxLength={255} pattern="[A-Za-z0-9._@+-]+" value={loginId.new_username} onChange={(event) => setLoginId({ ...loginId, new_username: event.target.value })} required /></label>
          <label>{t("Current password")}<input className="form-control" type="password" autoComplete="current-password" value={loginId.current_password} onChange={(event) => setLoginId({ ...loginId, current_password: event.target.value })} required /></label>
          <button className="btn btn-primary">{t("Save")}</button>
        </form>
      </Surface>
      <Surface title={t("Change password")} subtitle={t("Rotate the built-in admin password after install")}>
        <form className="credential-form" onSubmit={submitPassword}>
          <label>{t("Current password")}<input className="form-control" type="password" autoComplete="current-password" value={password.current_password} onChange={(event) => setPassword({ ...password, current_password: event.target.value })} required /></label>
          <label>{t("New password")}<input className="form-control" type="password" autoComplete="new-password" minLength={8} maxLength={256} value={password.new_password} onChange={(event) => setPassword({ ...password, new_password: event.target.value })} required /></label>
          <button className="btn btn-primary">{t("Save")}</button>
        </form>
      </Surface>
    </div>
  );
}

export function LayoutDiagnosticsSection({ t }: LayoutDiagnosticsSectionProps) {
  const [layoutAudit, setLayoutAudit] = useState<LayoutAudit | null>(null);
  return (
    <Surface
      title={t("Console diagnostics")}
      subtitle={t("Run a client-side layout check on the current console view")}
      action={<button className="btn btn-sm btn-outline-secondary icon-button" onClick={() => setLayoutAudit(runConsoleLayoutAudit())}><Icon name="display" /><span>{t("Run layout check")}</span></button>}
    >
      <LayoutAuditPanel audit={layoutAudit} t={t} />
    </Surface>
  );
}

export function LlmConfigurationSection({
  platformInfo,
  llmDiagnostics,
  llmSetupGuide,
  currentUser,
  onTestLlm,
  t,
}: LlmConfigurationSectionProps) {
  const [testingLlm, setTestingLlm] = useState(false);
  const llmRows = buildLlmRows(platformInfo?.llm, t);
  const canTestLlm = ["admin", "operator"].includes(String(currentUser?.role || ""));

  async function submitLlmTest() {
    if (!window.confirm(t("Run a live LLM connectivity test?"))) {
      return;
    }
    setTestingLlm(true);
    try {
      await onTestLlm();
    } finally {
      setTestingLlm(false);
    }
  }

  return (
    <Surface
      title={t("LLM configuration")}
      subtitle={t("Provider, model, and Secret wiring status")}
      action={canTestLlm && (
        <button className="btn btn-sm btn-outline-secondary icon-button" onClick={submitLlmTest} disabled={testingLlm}>
          <Icon name={testingLlm ? "arrow-repeat" : "stars"} />
          <span>{testingLlm ? "..." : t("Test LLM")}</span>
        </button>
      )}
    >
      <div className="settings-note">
        <Icon name="shield-lock" />
        <span>{t("LLM secrets are read from environment variables or Kubernetes Secret. API keys are never rendered in the browser.")}</span>
      </div>
      <InfoGrid rows={llmRows} />
      <LlmDiagnosticsPanel diagnostics={llmDiagnostics} t={t} />
      <LlmSetupGuidePanel guide={llmSetupGuide} t={t} />
    </Surface>
  );
}

export function NotificationDeliverySection({
  platformInfo,
  notificationHistory,
  currentUser,
  onTestNotification,
  t,
}: NotificationDeliverySectionProps) {
  const [testingNotification, setTestingNotification] = useState(false);
  const notificationRows = buildNotificationRows(platformInfo?.notification, t);
  const canTestNotification = ["admin", "operator"].includes(String(currentUser?.role || ""));
  const canViewNotificationHistory = ["admin", "auditor"].includes(String(currentUser?.role || ""));

  async function submitNotificationTest() {
    if (!window.confirm(t("Send a test notification to configured delivery targets?"))) {
      return;
    }
    setTestingNotification(true);
    try {
      await onTestNotification();
    } finally {
      setTestingNotification(false);
    }
  }

  return (
    <Surface
      title={t("Notification delivery")}
      subtitle={t("Slack/SIEM delivery status and test controls")}
      action={canTestNotification && (
        <button className="btn btn-sm btn-outline-secondary icon-button" onClick={submitNotificationTest} disabled={testingNotification}>
          <Icon name={testingNotification ? "arrow-repeat" : "send-check"} />
          <span>{testingNotification ? "..." : t("Test notification")}</span>
        </button>
      )}
    >
      <div className="settings-note">
        <Icon name="shield-lock" />
        <span>{t("Notification secrets are read from environment variables or Kubernetes Secret. Target URLs and tokens are never rendered in the browser.")}</span>
      </div>
      <InfoGrid rows={notificationRows} />
      <NotificationHistory events={notificationHistory} canView={canViewNotificationHistory} t={t} />
    </Surface>
  );
}

export function OperationsRuntimeSection({ platformInfo, t }: OperationsRuntimeSectionProps) {
  const rows = buildOperationsRows(platformInfo?.operations, t);
  return (
    <Surface title={t("Operations runtime")} subtitle={t("Scheduled collection, analysis pipeline, and metrics exposure")}>
      <div className="settings-note">
        <Icon name="activity" />
        <span>{t("These values reflect backend runtime configuration. They help operators confirm whether autonomous collection and observability are active.")}</span>
      </div>
      <InfoGrid rows={rows} />
    </Surface>
  );
}

export function CatalogSection({
  catalogDetail,
  platformInfo,
  catalogOverrideDrafts,
  currentUser,
  onPreviewCatalogOverride,
  onCreateCatalogOverrideDraft,
  onDecideCatalogOverrideDraft,
  onLoadCatalogOverrideHandoff,
  onCreateCatalogGitOpsChange,
  onLoadCatalogGitOpsChanges,
  onUpdateGitOpsOutcome,
  t,
}: CatalogSectionProps) {
  const [overrideJson, setOverrideJson] = useState(DEFAULT_CATALOG_OVERRIDE_JSON);
  const [reason, setReason] = useState("");
  const [preview, setPreview] = useState<CatalogOverridePreviewResponse | null>(null);
  const [previewing, setPreviewing] = useState(false);
  const [savingDraft, setSavingDraft] = useState(false);
  const [draftBusyId, setDraftBusyId] = useState("");
  const [handoffs, setHandoffs] = useState<Record<string, CatalogOverrideHandoff>>({});
  const [gitOpsChanges, setGitOpsChanges] = useState<Record<string, GitOpsChange[]>>({});
  const summary = catalogDetail?.summary || platformInfo?.catalog || {};
  const collectors = Object.entries(catalogDetail?.collectors || {});
  const actions = Object.entries(catalogDetail?.actions || {});
  const rules = Object.entries(catalogDetail?.rules || {});
  const defaultCollectors = catalogDetail?.collector_selection?.default_collectors || [];
  const alertSelections = Object.entries(catalogDetail?.collector_selection?.alerts || {}).slice(0, 8);
  const canPreview = ["admin", "operator"].includes(String(currentUser?.role || ""));
  const canViewDrafts = ["admin", "operator", "approver", "auditor"].includes(String(currentUser?.role || ""));
  async function submitPreview(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    try {
      JSON.parse(overrideJson);
    } catch (error) {
      setPreview({
        valid: false,
        message: error instanceof Error ? error.message : t("Invalid JSON syntax."),
        diff: [],
        diff_count: 0,
      });
      return;
    }
    setPreviewing(true);
    try {
      setPreview(await onPreviewCatalogOverride(overrideJson, reason));
    } finally {
      setPreviewing(false);
    }
  }
  async function saveDraft() {
    if (!preview?.valid) {
      return;
    }
    if (!window.confirm(t("Save this validated override as a review draft?"))) {
      return;
    }
    setSavingDraft(true);
    try {
      await onCreateCatalogOverrideDraft(overrideJson, reason);
    } finally {
      setSavingDraft(false);
    }
  }
  async function decideDraft(draft: CatalogOverrideDraft, decision: "approve" | "reject" | "discard") {
    const label = decision === "approve" ? t("Approve") : decision === "reject" ? t("Reject") : t("Discard");
    if (!window.confirm(`${label} ${draft.draft_id}?`)) {
      return;
    }
    const note = window.prompt(t("Decision note"), "") || "";
    setDraftBusyId(`${draft.draft_id}:${decision}`);
    try {
      await onDecideCatalogOverrideDraft(draft, decision, note);
    } finally {
      setDraftBusyId("");
    }
  }
  async function showHandoff(draft: CatalogOverrideDraft) {
    setDraftBusyId(`${draft.draft_id}:handoff`);
    try {
      const [handoff, trackedChanges] = await Promise.all([
        onLoadCatalogOverrideHandoff(draft),
        onLoadCatalogGitOpsChanges(draft),
      ]);
      setHandoffs((current) => ({ ...current, [draft.draft_id]: handoff }));
      setGitOpsChanges((current) => ({ ...current, [draft.draft_id]: trackedChanges }));
    } finally {
      setDraftBusyId("");
    }
  }
  async function createGitOpsChange(draft: CatalogOverrideDraft) {
    if (!window.confirm(t("Create a draft GitOps pull request for this approved catalog change?"))) {
      return;
    }
    setDraftBusyId(`${draft.draft_id}:gitops`);
    try {
      const change = await onCreateCatalogGitOpsChange(draft);
      setGitOpsChanges((current) => ({ ...current, [draft.draft_id]: [change] }));
    } finally {
      setDraftBusyId("");
    }
  }
  async function updateGitOpsOutcome(change: GitOpsChange, state: GitOpsDeploymentState) {
    const verification = window.prompt(t("Verification result"), change.verification_result || "") || "";
    const rollback = state === "rolled_back"
      ? window.prompt(t("Rollback reference"), change.rollback_reference || "") || ""
      : change.rollback_reference || "";
    if (!window.confirm(`${t("Record deployment state")}: ${state}?`)) {
      return;
    }
    setDraftBusyId(`${change.change_id}:outcome`);
    try {
      const updated = await onUpdateGitOpsOutcome(change, state, verification, rollback);
      setGitOpsChanges((current) => ({
        ...current,
        [change.source_id || ""]: (current[change.source_id || ""] || []).map((item) =>
          item.change_id === updated.change_id ? updated : item),
      }));
    } finally {
      setDraftBusyId("");
    }
  }
  const rows: InfoRow[] = [
    { key: "catalog.schema", label: t("Schema"), value: stringValue(summary.schema_version) || "n/a" },
    { key: "catalog.version", label: t("Version"), value: stringValue(summary.version) || "n/a" },
    { key: "catalog.source", label: t("Source"), value: stringValue(summary.source) || "n/a" },
    { key: "catalog.checksum", label: t("Checksum"), value: shortChecksum(stringValue(summary.checksum)) },
    {
      key: "catalog.external",
      label: t("External override"),
      value: summary.external_override_active ? t("Enabled") : t("Disabled"),
      tone: summary.external_override_active ? "ok" : "muted",
    },
    {
      key: "catalog.execution",
      label: t("Agent action execution"),
      value: summary.action_plan_execution_enabled ? t("Enabled") : t("Disabled"),
      tone: summary.action_plan_execution_enabled ? "warn" : "ok",
    },
  ];
  return (
    <Surface title={t("Operational catalog")} subtitle={t("Collector selection, action policy, and rule detector catalog")}>
      <InfoGrid rows={rows} />
      <div className="catalog-defaults">
        <strong>{t("Default collectors")}</strong>
        <div>
          {defaultCollectors.map((collector) => <code key={collector}>{collector}</code>)}
          {!defaultCollectors.length && <span className="text-muted">{t("No catalog data loaded.")}</span>}
        </div>
      </div>
      <div className="catalog-grid">
        <CatalogList
          title={t("Collectors")}
          items={collectors.slice(0, 12).map(([key, collector]) => ({
            key,
            title: key,
            description: collector.description || "-",
            badge: collector.enabled === false ? t("Disabled") : t("Enabled"),
            badgeTone: collector.enabled === false ? "red" : "green",
            meta: Array.isArray(collector.permission_modes) ? collector.permission_modes.join(", ") : "",
          }))}
          empty={t("No catalog data loaded.")}
          t={t}
        />
        <CatalogList
          title={t("Actions")}
          items={actions.slice(0, 12).map(([key, action]) => ({
            key,
            title: key,
            description: action.action || action.reason || "-",
            badge: action.policy || "unknown",
            badgeTone: policyTone(action.policy),
            meta: `${action.automation_mode || "manual"} / executable=${action.plan?.executable === true ? "true" : "false"}`,
          }))}
          empty={t("No catalog data loaded.")}
          t={t}
        />
        <CatalogList
          title={t("Rules")}
          items={rules.slice(0, 12).map(([key, rule]) => ({
            key,
            title: key,
            description: Array.isArray(rule.signals) ? rule.signals.join(", ") : rule.component || "-",
            badge: rule.enabled === false ? t("Disabled") : t("Enabled"),
            badgeTone: rule.enabled === false ? "red" : "green",
            meta: rule.component || rule.detector || "",
          }))}
          empty={t("No catalog data loaded.")}
          t={t}
        />
      </div>
      <div className="catalog-alerts">
        <div className="notification-history-head">
          <h3>{t("Alert collector selection")}</h3>
          <span>{t("Collectors requested by alert name")}</span>
        </div>
        {alertSelections.length ? (
          <div className="catalog-alert-list">
            {alertSelections.map(([alertName, selection]) => (
              <article key={alertName}>
                <strong>{alertName}</strong>
                <div>{selection.map((collector) => <code key={`${alertName}-${collector}`}>{collector}</code>)}</div>
              </article>
            ))}
          </div>
        ) : (
          <EmptyState message={t("No catalog data loaded.")} />
        )}
      </div>
      {canPreview && (
        <div className="catalog-preview">
          <div className="notification-history-head">
            <h3>{t("Override preview")}</h3>
            <span>{t("Validate JSON and inspect catalog changes before deployment")}</span>
          </div>
          <form className="catalog-preview-form" onSubmit={submitPreview}>
            <label>
              {t("Override JSON")}
              <textarea
                className="form-control monospace-control"
                rows={12}
                spellCheck={false}
                value={overrideJson}
                onChange={(event) => setOverrideJson(event.target.value)}
                required
              />
            </label>
            <label>
              {t("Reason")}
              <input
                className="form-control"
                maxLength={500}
                value={reason}
                onChange={(event) => setReason(event.target.value)}
                placeholder={t("Change reason for audit")}
              />
            </label>
            <div className="catalog-preview-actions">
              <button className="btn btn-primary icon-button" disabled={previewing}>
                <Icon name={previewing ? "arrow-repeat" : "check2-square"} />
                <span>{previewing ? "..." : t("Preview override")}</span>
              </button>
              <button type="button" className="btn btn-outline-secondary" onClick={() => setOverrideJson(DEFAULT_CATALOG_OVERRIDE_JSON)}>
                {t("Load sample")}
              </button>
            </div>
          </form>
          <CatalogOverridePreviewResult preview={preview} t={t} />
          {preview?.valid && (
            <div className="catalog-preview-save">
              <button className="btn btn-success icon-button" onClick={saveDraft} disabled={savingDraft}>
                <Icon name={savingDraft ? "arrow-repeat" : "journal-plus"} />
                <span>{savingDraft ? "..." : t("Save draft")}</span>
              </button>
              <span>{t("Draft approval records review only. The console does not apply catalog changes.")}</span>
            </div>
          )}
        </div>
      )}
      {canViewDrafts && (
        <div className="catalog-drafts">
          <div className="notification-history-head">
            <h3>{t("Override drafts")}</h3>
            <span>{t("Approved drafts are handed off to GitOps or a runbook.")}</span>
          </div>
          <CatalogOverrideDraftsPanel
            drafts={catalogOverrideDrafts}
            currentUser={currentUser}
            draftBusyId={draftBusyId}
            handoffs={handoffs}
            gitOpsEnabled={platformInfo?.gitops?.enabled === true}
            gitOpsChanges={gitOpsChanges}
            onDecide={decideDraft}
            onShowHandoff={showHandoff}
            onCreateGitOps={createGitOpsChange}
            onUpdateGitOpsOutcome={updateGitOpsOutcome}
            t={t}
          />
        </div>
      )}
    </Surface>
  );
}

function CatalogOverridePreviewResult({
  preview,
  t,
}: {
  preview: CatalogOverridePreviewResponse | null;
  t: TFunction;
}) {
  if (!preview) {
    return <EmptyState message={t("No override preview has been run yet.")} />;
  }
  const diff = Array.isArray(preview.diff) ? preview.diff : [];
  return (
    <div className={`catalog-preview-result ${preview.valid ? "valid" : "invalid"}`}>
      <div className="catalog-preview-status">
        <StatusBadge value={preview.valid ? t("Valid") : t("Rejected")} tone={preview.valid ? "green" : "red"} t={t} />
        <span>{preview.message || "-"}</span>
      </div>
      {preview.summary && (
        <InfoGrid rows={[
          { key: "preview.version", label: t("Proposed version"), value: stringValue(preview.summary.version) || "n/a" },
          { key: "preview.checksum", label: t("Proposed checksum"), value: shortChecksum(stringValue(preview.summary.checksum)) },
          { key: "preview.collectors", label: t("Collectors"), value: String(preview.summary.collector_count ?? "0") },
          { key: "preview.actions", label: t("Actions"), value: String(preview.summary.action_count ?? "0") },
          { key: "preview.rules", label: t("Rules"), value: String(preview.summary.rule_count ?? "0") },
        ]} />
      )}
      <div className="catalog-diff-head">
        <strong>{t("Change diff")}</strong>
        <span>{Number(preview.diff_count ?? diff.length)} {t("changes")}{preview.diff_truncated ? ` / ${t("truncated")}` : ""}</span>
      </div>
      {diff.length ? <CatalogDiffList diff={diff} t={t} /> : <EmptyState message={t("No catalog changes detected.")} />}
    </div>
  );
}

function CatalogDiffList({ diff, t }: { diff: CatalogDiffEntry[]; t: TFunction }) {
  return (
    <div className="catalog-diff-list">
      {diff.map((entry, index) => (
        <article key={`${entry.path}-${index}`} className="catalog-diff-item">
          <div>
            <code>{entry.path || "/"}</code>
            <StatusBadge value={String(entry.change_type || "changed")} tone={diffTone(entry.change_type)} t={t} />
          </div>
          <dl>
            <div>
              <dt>{t("Current")}</dt>
              <dd>{formatPreviewValue(entry.current_value)}</dd>
            </div>
            <div>
              <dt>{t("Proposed")}</dt>
              <dd>{formatPreviewValue(entry.proposed_value)}</dd>
            </div>
          </dl>
        </article>
      ))}
    </div>
  );
}

function CatalogOverrideDraftsPanel({
  drafts,
  currentUser,
  draftBusyId,
  handoffs,
  gitOpsEnabled,
  gitOpsChanges,
  onDecide,
  onShowHandoff,
  onCreateGitOps,
  onUpdateGitOpsOutcome,
  t,
}: {
  drafts: CatalogOverrideDraft[];
  currentUser: UserAccount | null;
  draftBusyId: string;
  handoffs: Record<string, CatalogOverrideHandoff>;
  gitOpsEnabled: boolean;
  gitOpsChanges: Record<string, GitOpsChange[]>;
  onDecide: (draft: CatalogOverrideDraft, decision: "approve" | "reject" | "discard") => void | Promise<void>;
  onShowHandoff: (draft: CatalogOverrideDraft) => void | Promise<void>;
  onCreateGitOps: (draft: CatalogOverrideDraft) => void | Promise<void>;
  onUpdateGitOpsOutcome: (change: GitOpsChange, state: GitOpsDeploymentState) => void | Promise<void>;
  t: TFunction;
}) {
  if (!drafts.length) {
    return <EmptyState message={t("No catalog override drafts.")} />;
  }
  const role = String(currentUser?.role || "");
  const canApprove = ["admin", "approver"].includes(role);
  const canDiscard = ["admin", "operator"].includes(role);
  const canHandoff = ["admin", "operator", "approver"].includes(role);
  return (
    <div className="catalog-draft-list">
      {drafts.map((draft) => {
        const handoff = handoffs[draft.draft_id];
        const trackedChanges = gitOpsChanges[draft.draft_id] || [];
        const diff = Array.isArray(draft.diff) ? draft.diff : [];
        return (
          <article className="catalog-draft-item" key={draft.draft_id}>
            <header>
              <div>
                <strong>{draft.draft_id}</strong>
                <span>{draft.reason || draft.validation_message || "-"}</span>
              </div>
              <StatusBadge value={draft.status || "draft"} tone={draftStatusTone(draft.status)} t={t} />
            </header>
            <div className="catalog-draft-meta">
              <span>{t("Requested by")}: {draft.requested_by || "-"}</span>
              <span>{t("Created")}: {formatDate(draft.created_at)}</span>
              <span>{t("Reviewed by")}: {draft.reviewed_by || "-"}</span>
              <span>{t("Reviewed")}: {formatDate(draft.reviewed_at)}</span>
              <span>{t("Diff")}: {diff.length}{draft.diff_truncated ? ` / ${t("truncated")}` : ""}</span>
            </div>
            <div className="catalog-draft-paths">
              {diff.slice(0, 6).map((entry, index) => <code key={`${draft.draft_id}-${entry.path}-${index}`}>{entry.path || "/"}</code>)}
              {!diff.length && <span>{t("No catalog changes detected.")}</span>}
            </div>
            <div className="catalog-draft-actions">
              {draft.status === "draft" && canApprove && (
                <>
                  <button className="btn btn-sm btn-success" disabled={draftBusyId === `${draft.draft_id}:approve`} onClick={() => onDecide(draft, "approve")}>{t("Approve")}</button>
                  <button className="btn btn-sm btn-outline-danger" disabled={draftBusyId === `${draft.draft_id}:reject`} onClick={() => onDecide(draft, "reject")}>{t("Reject")}</button>
                </>
              )}
              {draft.status === "draft" && canDiscard && (
                <button className="btn btn-sm btn-outline-secondary" disabled={draftBusyId === `${draft.draft_id}:discard`} onClick={() => onDecide(draft, "discard")}>{t("Discard")}</button>
              )}
              {draft.status === "approved" && canHandoff && (
                <button className="btn btn-sm btn-primary icon-button" disabled={draftBusyId === `${draft.draft_id}:handoff`} onClick={() => onShowHandoff(draft)}>
                  <Icon name={draftBusyId === `${draft.draft_id}:handoff` ? "arrow-repeat" : "git"} />
                  <span>{t("Show handoff")}</span>
                </button>
              )}
              {draft.status === "approved" && gitOpsEnabled && canDiscard && (
                <button className="btn btn-sm btn-success icon-button" disabled={draftBusyId === `${draft.draft_id}:gitops`} onClick={() => onCreateGitOps(draft)}>
                  <Icon name={draftBusyId === `${draft.draft_id}:gitops` ? "arrow-repeat" : "git"} />
                  <span>{t("Create GitOps PR")}</span>
                </button>
              )}
            </div>
            {handoff && <CatalogOverrideHandoffPanel handoff={handoff} t={t} />}
            {trackedChanges.map((change) => (
              <GitOpsChangePanel
                key={change.change_id}
                change={change}
                busy={draftBusyId === `${change.change_id}:outcome`}
                canUpdate={canDiscard}
                onUpdate={onUpdateGitOpsOutcome}
                t={t}
              />
            ))}
          </article>
        );
      })}
    </div>
  );
}

function GitOpsChangePanel({
  change,
  busy,
  canUpdate,
  onUpdate,
  t,
}: {
  change: GitOpsChange;
  busy: boolean;
  canUpdate: boolean;
  onUpdate: (change: GitOpsChange, state: GitOpsDeploymentState) => void | Promise<void>;
  t: TFunction;
}) {
  const nextStates: GitOpsDeploymentState[] = change.pull_request_state !== "merged" ? []
    : change.deployment_state === "pending" ? ["in_progress"]
      : change.deployment_state === "in_progress" ? ["succeeded", "failed"]
        : ["succeeded", "failed"].includes(String(change.deployment_state)) ? ["rolled_back"] : [];
  return (
    <div className="catalog-handoff gitops-change-panel">
      <div className="catalog-preview-status">
        <strong>{change.repository || "GitOps"} #{change.pull_request_number || "-"}</strong>
        <StatusBadge value={change.pull_request_state || "creating"} tone={change.pull_request_state === "merged" ? "green" : change.pull_request_state === "failed" ? "red" : "blue"} t={t} />
        <StatusBadge value={change.deployment_state || "pending"} tone={change.deployment_state === "succeeded" ? "green" : change.deployment_state === "failed" ? "red" : "gray"} t={t} />
      </div>
      {change.pull_request_url && <a href={change.pull_request_url} target="_blank" rel="noreferrer">{t("Open pull request")}</a>}
      <code>{change.branch || "-"}</code>
      {change.error_message && <div className="alert alert-danger mb-0">{change.error_message}</div>}
      {change.verification_result && <p>{change.verification_result}</p>}
      {change.rollback_reference && <p>{t("Rollback reference")}: {change.rollback_reference}</p>}
      {canUpdate && nextStates.length > 0 && (
        <div className="catalog-draft-actions">
          {nextStates.map((state) => (
            <button key={state} className="btn btn-sm btn-outline-secondary" disabled={busy} onClick={() => onUpdate(change, state)}>
              {t(state === "in_progress" ? "Start deployment" : state === "succeeded" ? "Mark succeeded" : state === "failed" ? "Mark failed" : "Record rollback")}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}

function CatalogOverrideHandoffPanel({ handoff, t }: { handoff: CatalogOverrideHandoff; t: TFunction }) {
  const files = handoff.files || {};
  return (
    <div className="catalog-handoff">
      <strong>{handoff.recommendation || t("Use GitOps PR or a controlled runbook.")}</strong>
      <ol>
        {(handoff.runbook_steps || []).map((step, index) => <li key={`${step}-${index}`}>{step}</li>)}
      </ol>
      <label>
        {t("Pull request title")}
        <input className="form-control" readOnly value={handoff.pull_request_title || ""} />
      </label>
      <label>
        {t("Pull request body")}
        <textarea className="form-control monospace-control" rows={8} readOnly value={handoff.pull_request_body || ""} />
      </label>
      {Object.entries(files).map(([path, content]) => (
        <label key={path}>
          {path}
          <textarea className="form-control monospace-control" rows={8} readOnly value={content} />
        </label>
      ))}
    </div>
  );
}

export function PlatformInfoSection({ platformInfo, t }: PlatformInfoSectionProps) {
  const infoRows = platformInfoRows(platformInfo, t) as InfoRow[];
  return (
    <Surface title={t("Platform info")} subtitle={t("Protocol compatibility and export integrity")}>
      <InfoGrid rows={infoRows} />
    </Surface>
  );
}

function CatalogList({
  title,
  items,
  empty,
  t,
}: {
  title: string;
  items: Array<{ key: string; title: string; description: string; badge: string; badgeTone: string; meta: string }>;
  empty: string;
  t: TFunction;
}) {
  return (
    <section className="catalog-list">
      <h3>{title}</h3>
      {items.length ? items.map((item) => (
        <article key={item.key} className="catalog-item">
          <div>
            <strong>{item.title}</strong>
            <span>{item.description}</span>
            {item.meta && <small>{item.meta}</small>}
          </div>
          <StatusBadge value={item.badge} tone={item.badgeTone} t={t} />
        </article>
      )) : <EmptyState message={empty} />}
    </section>
  );
}

function InfoGrid({ rows }: { rows: InfoRow[] }) {
  return (
    <div className="info-grid">
      {rows.map((row) => (
        <div key={row.key} className={row.tone ? `info-card-${row.tone}` : ""}>
          <span>{row.label}</span>
          <strong>{row.value}</strong>
        </div>
      ))}
    </div>
  );
}

function buildNotificationRows(
  notification: NotificationConfigurationInfo | undefined,
  t: TFunction,
): InfoRow[] {
  const enabled = Boolean(notification?.enabled);
  const slackConfigured = Boolean(notification?.slack_configured ?? notification?.slackConfigured);
  const webhookConfigured = Boolean(notification?.webhook_configured ?? notification?.webhookConfigured);
  const tokenConfigured = Boolean(notification?.webhook_token_configured ?? notification?.webhookTokenConfigured);
  const channels = Array.isArray(notification?.channels) ? notification.channels : [];
  const minimumSeverity = stringValue(notification?.minimum_severity ?? notification?.minimumSeverity) || "critical";
  return [
    { key: "notification.enabled", label: t("Enabled"), value: enabled ? t("Enabled") : t("Disabled"), tone: enabled ? "ok" : "muted" },
    { key: "notification.channels", label: t("Configured targets"), value: channels.length ? channels.join(", ") : t("No"), tone: channels.length ? "ok" : "warn" },
    { key: "notification.slack", label: t("Slack webhook"), value: slackConfigured ? t("Configured") : t("Missing"), tone: slackConfigured ? "ok" : "muted" },
    { key: "notification.webhook", label: t("Generic webhook"), value: webhookConfigured ? t("Configured") : t("Missing"), tone: webhookConfigured ? "ok" : "muted" },
    { key: "notification.token", label: t("Bearer token"), value: tokenConfigured ? t("Configured") : t("Not required"), tone: tokenConfigured ? "ok" : "muted" },
    { key: "notification.severity", label: t("Minimum severity"), value: t(minimumSeverity), tone: minimumSeverity === "critical" ? "muted" : "warn" },
    { key: "notification.attempts", label: t("Attempts"), value: numberValue(notification?.max_attempts ?? notification?.maxAttempts, 2) },
    { key: "notification.timeout", label: t("Timeout"), value: `${numberValue(notification?.timeout_seconds ?? notification?.timeoutSeconds, 5)}s` },
  ];
}

function buildOperationsRows(operations: unknown, t: TFunction): InfoRow[] {
  const value = operations && typeof operations === "object" && !Array.isArray(operations)
    ? operations as Record<string, unknown>
    : {};
  const scheduledMonitoring = Boolean(value.scheduled_monitoring_enabled ?? value.scheduledMonitoringEnabled);
  const collectHealthyAgents = Boolean(value.collect_healthy_agents ?? value.collectHealthyAgents);
  const pipelineEnabled = Boolean(value.analysis_pipeline_enabled ?? value.analysisPipelineEnabled);
  const observabilityEnabled = Boolean(value.observability_enabled ?? value.observabilityEnabled);
  const metricsTokenConfigured = Boolean(value.metrics_token_configured ?? value.metricsTokenConfigured);
  return [
    {
      key: "operations.scheduled_monitoring_enabled",
      label: t("Scheduled monitoring"),
      value: scheduledMonitoring ? t("Enabled") : t("Disabled"),
      tone: scheduledMonitoring ? "ok" : "muted",
    },
    {
      key: "operations.scheduled_monitoring_interval",
      label: t("Collection interval"),
      value: `${numberValue(value.scheduled_monitoring_interval_ms ?? value.scheduledMonitoringIntervalMs, 0)}ms`,
    },
    {
      key: "operations.collect_healthy_agents",
      label: t("Healthy agent baseline"),
      value: collectHealthyAgents ? t("Enabled") : t("Disabled"),
      tone: collectHealthyAgents ? "ok" : "muted",
    },
    {
      key: "operations.health_cadence",
      label: t("Collection cadence"),
      value: `${numberValue(value.healthy_interval_minutes ?? value.healthyIntervalMinutes, 0)}m / ${numberValue(value.degraded_interval_minutes ?? value.degradedIntervalMinutes, 0)}m / ${numberValue(value.stale_interval_minutes ?? value.staleIntervalMinutes, 0)}m`,
    },
    {
      key: "operations.analysis_pipeline_enabled",
      label: t("Analysis pipeline"),
      value: pipelineEnabled ? t("Enabled") : t("Disabled"),
      tone: pipelineEnabled ? "ok" : "warn",
    },
    {
      key: "operations.analysis_pipeline_batch",
      label: t("Pipeline batch"),
      value: `${numberValue(value.analysis_pipeline_batch_size ?? value.analysisPipelineBatchSize, 0)} / ${numberValue(value.analysis_pipeline_max_attempts ?? value.analysisPipelineMaxAttempts, 0)} attempts`,
    },
    {
      key: "operations.observability_enabled",
      label: t("Observability"),
      value: observabilityEnabled ? t("Enabled") : t("Disabled"),
      tone: observabilityEnabled ? "ok" : "muted",
    },
    {
      key: "operations.metrics_token_configured",
      label: t("Metrics token"),
      value: metricsTokenConfigured ? t("Configured") : t("Not configured"),
      tone: metricsTokenConfigured ? "ok" : "warn",
    },
  ];
}

function buildLlmRows(llm: LlmConfigurationInfo | undefined, t: TFunction): InfoRow[] {
  const enabled = Boolean(llm?.enabled);
  const credentialRequired = Boolean(llm?.credential_required ?? llm?.credentialRequired);
  const credentialConfigured = Boolean(llm?.credential_configured ?? llm?.credentialConfigured);
  const baseUrlRequired = Boolean(llm?.base_url_required ?? llm?.baseUrlRequired);
  const baseUrlConfigured = Boolean(llm?.base_url_configured ?? llm?.baseUrlConfigured);
  const credentialEnv = stringValue(llm?.credential_env ?? llm?.credentialEnv);
  const baseUrlEnv = stringValue(llm?.base_url_env ?? llm?.baseUrlEnv);
  const springAiChatModel = stringValue(llm?.spring_ai_chat_model ?? llm?.springAiChatModel) || "none";
  const credentialValue = !enabled
    ? t("Disabled")
    : !credentialRequired
      ? t("Not required")
      : credentialConfigured
        ? t("Configured")
        : `${t("Missing")} ${credentialEnv || t("Credential env")}`;
  return [
    { key: "llm.enabled", label: t("Enabled"), value: enabled ? t("Enabled") : t("Disabled"), tone: enabled ? "ok" : "muted" },
    { key: "llm.provider", label: t("Provider"), value: stringValue(llm?.provider) || "none" },
    { key: "llm.model", label: t("Model"), value: stringValue(llm?.model) || "n/a" },
    { key: "llm.spring_ai_chat_model", label: t("Spring AI chat model"), value: springAiChatModel, tone: springAiChatModel === "none" ? "muted" : "ok" },
    { key: "llm.credential", label: t("Credential"), value: credentialValue, tone: !enabled || !credentialRequired ? "muted" : credentialConfigured ? "ok" : "warn" },
    { key: "llm.credential_env", label: t("Credential env"), value: credentialRequired ? credentialEnv || t("Credential env") : t("Not required") },
    { key: "llm.base_url_env", label: t("Base URL env"), value: baseUrlEnv ? (baseUrlRequired && !baseUrlConfigured ? `${t("Missing")} ${baseUrlEnv}` : baseUrlEnv) : t("Provider default"), tone: baseUrlRequired && !baseUrlConfigured ? "warn" : "muted" },
    { key: "llm.timeout", label: t("Timeout"), value: `${numberValue(llm?.timeout_seconds ?? llm?.timeoutSeconds, 30)}s` },
    { key: "llm.max_attempts", label: t("Attempts"), value: numberValue(llm?.max_attempts ?? llm?.maxAttempts, 2) },
    { key: "llm.max_output_tokens", label: t("Max output tokens"), value: numberValue(llm?.max_output_tokens ?? llm?.maxOutputTokens, 1800) },
    { key: "llm.circuit_breaker", label: t("Circuit breaker"), value: `${numberValue(llm?.failure_threshold ?? llm?.failureThreshold, 3)} / ${numberValue(llm?.cooldown_seconds ?? llm?.cooldownSeconds, 60)}s` },
  ];
}

function LlmDiagnosticsPanel({
  diagnostics,
  t,
}: {
  diagnostics: LlmDiagnosticResponse | null;
  t: TFunction;
}) {
  if (!diagnostics) {
    return (
      <div className="llm-diagnostics">
        <div className="empty-state compact">{t("No LLM diagnostics loaded.")}</div>
      </div>
    );
  }
  const checks = Array.isArray(diagnostics.checks) ? diagnostics.checks : [];
  return (
    <div className="llm-diagnostics">
      <div className="llm-diagnostics-head">
        <div>
          <h3>{t("LLM diagnostics")}</h3>
          <span>{t("Configuration checks")}</span>
        </div>
        <StatusBadge value={diagnostics.outcome || "unknown"} tone={diagnosticTone(diagnostics.outcome)} t={t} />
      </div>
      {!checks.length ? (
        <div className="empty-state compact">{t("No LLM diagnostics loaded.")}</div>
      ) : (
        <div className="llm-diagnostic-list">
          {checks.map((check, index) => (
            <LlmDiagnosticItem key={`${check.key || "check"}-${index}`} check={check} t={t} />
          ))}
        </div>
      )}
    </div>
  );
}

function LlmDiagnosticItem({ check, t }: { check: LlmDiagnosticCheck; t: TFunction }) {
  return (
    <article className="llm-diagnostic-item">
      <div className="llm-diagnostic-main">
        <strong>{check.key || "-"}</strong>
        <span>{check.message || "-"}</span>
        {check.remediation && (
          <small><b>{t("Remediation")}:</b> {check.remediation}</small>
        )}
      </div>
      <div className="llm-diagnostic-meta">
        <StatusBadge value={check.status || "unknown"} tone={diagnosticTone(check.status)} t={t} />
      </div>
    </article>
  );
}

function LlmSetupGuidePanel({
  guide,
  t,
}: {
  guide: LlmSetupGuideResponse | null;
  t: TFunction;
}) {
  if (!guide) {
    return (
      <div className="llm-setup-guide">
        <div className="empty-state compact">{t("No LLM setup guide loaded.")}</div>
      </div>
    );
  }
  const providers = Array.isArray(guide.providers) ? guide.providers : [];
  const docsPath = stringValue(guide.docs_path ?? guide.docsPath);
  const restartRequired = Boolean(guide.restart_required ?? guide.restartRequired);
  const secretStorage = stringValue(guide.secret_storage ?? guide.secretStorage);
  return (
    <div className="llm-setup-guide">
      <div className="llm-setup-head">
        <div>
          <h3>{t("Provider setup guide")}</h3>
          <span>{docsPath || t("LLM setup guide")}</span>
        </div>
        <StatusBadge value={restartRequired ? "restart_required" : "runtime_reload"} tone={restartRequired ? "amber" : "green"} t={t} />
      </div>
      {secretStorage && (
        <div className="settings-note compact">
          <Icon name="key" />
          <span>{t(secretStorage)}</span>
        </div>
      )}
      {!providers.length ? (
        <div className="empty-state compact">{t("No LLM setup guide loaded.")}</div>
      ) : (
        <div className="llm-provider-grid">
          {providers.map((provider) => (
            <LlmProviderSetupCard
              key={provider.provider || provider.display_name || provider.displayName}
              provider={provider}
              t={t}
            />
          ))}
        </div>
      )}
    </div>
  );
}

function LlmProviderSetupCard({
  provider,
  t,
}: {
  provider: LlmProviderSetupOption;
  t: TFunction;
}) {
  const displayName = stringValue(provider.display_name ?? provider.displayName) || stringValue(provider.provider) || "-";
  const springAiChatModel = stringValue(provider.spring_ai_chat_model ?? provider.springAiChatModel) || "-";
  const credentialEnv = stringValue(provider.credential_env ?? provider.credentialEnv);
  const baseUrlEnv = stringValue(provider.base_url_env ?? provider.baseUrlEnv);
  const credentialRequired = Boolean(provider.credential_required ?? provider.credentialRequired);
  const baseUrlRequired = Boolean(provider.base_url_required ?? provider.baseUrlRequired);
  const examples = Array.isArray(provider.model_examples)
    ? provider.model_examples
    : Array.isArray(provider.modelExamples)
      ? provider.modelExamples
      : [];
  return (
    <article className="llm-provider-card">
      <header>
        <div>
          <strong>{displayName}</strong>
          <span>{provider.provider}</span>
        </div>
        <StatusBadge value={credentialRequired ? "credential_required" : "no_api_key_required"} tone={credentialRequired ? "amber" : "green"} t={t} />
      </header>
      <div className="llm-provider-kv">
        <span>{t("Spring AI chat model")}</span>
        <code>{springAiChatModel}</code>
        <span>{t("Credential env")}</span>
        <code>{credentialEnv || t("Not required")}</code>
        <span>{t("Base URL env")}</span>
        <code>{baseUrlEnv || t("Provider default")}</code>
        <span>{t("Base URL")}</span>
        <strong>{baseUrlRequired ? t("Required") : t("Optional")}</strong>
      </div>
      {!!examples.length && (
        <div className="llm-model-examples">
          <span>{t("Model examples")}</span>
          <div>{examples.map((example) => <code key={example}>{example}</code>)}</div>
        </div>
      )}
      {provider.note && <p>{t(provider.note)}</p>}
    </article>
  );
}

function NotificationHistory({ events, canView, t }: { events: AuditEventView[]; canView: boolean; t: TFunction }) {
  if (!canView) {
    return (
      <div className="notification-history">
        <h3>{t("Delivery history")}</h3>
        <div className="empty-state compact">{t("Notification delivery history is visible to admin/auditor users.")}</div>
      </div>
    );
  }
  const visibleEvents = events.slice(0, 8);
  return (
    <div className="notification-history">
      <div className="notification-history-head">
        <h3>{t("Delivery history")}</h3>
        <span>{t("Recent notification delivery audit records")}</span>
      </div>
      {!visibleEvents.length ? (
        <div className="empty-state compact">{t("No notification delivery history.")}</div>
      ) : (
        <div className="notification-history-list">
          {visibleEvents.map((event) => (
            <article key={event.audit_event_id} className="notification-history-item">
              <div className="notification-history-main">
                <strong>{event.event_type}</strong>
                <span>{notificationSummary(event)}</span>
              </div>
              <div className="notification-history-meta">
                <StatusBadge value={event.outcome} t={t} />
                <span>{t("Channel")}: {notificationChannels(event)}</span>
                <span>{t("Attempts")}: {notificationAttempts(event)}</span>
                <span>{t("Status code")}: {notificationStatusCode(event)}</span>
                <span>{formatDate(event.created_at)}</span>
              </div>
            </article>
          ))}
        </div>
      )}
    </div>
  );
}

export function LayoutAuditPanel({ audit, t }: LayoutAuditPanelProps) {
  if (!audit) return <EmptyState message={t("Run a check to inspect the current viewport for overflow or clipped text.")} />;
  const issueCount = audit.offscreen.length + audit.overflowed.length + audit.clipped.length + (audit.page_overflow_x ? 1 : 0);
  return (
    <div className="diagnostics-panel">
      <div className="diagnostic-summary">
        <div><span>{t("Viewport")}</span><strong>{audit.viewport_width} x {audit.viewport_height}</strong></div>
        <div><span>{t("Page overflow")}</span><strong>{audit.page_overflow_x ? t("Yes") : t("No")}</strong></div>
        <div><span>{t("Offscreen elements")}</span><strong>{audit.offscreen.length}</strong></div>
        <div><span>{t("Overflow candidates")}</span><strong>{audit.overflowed.length}</strong></div>
        <div><span>{t("Clipped text candidates")}</span><strong>{audit.clipped.length}</strong></div>
        <div><span>{t("Last checked")}</span><strong>{formatDate(audit.checked_at)}</strong></div>
      </div>
      {issueCount === 0 ? (
        <div className="diagnostic-ok"><Icon name="check2-circle" /><span>{t("No layout issues detected.")}</span></div>
      ) : (
        <div className="diagnostic-list">
          <DiagnosticGroup title={t("Offscreen elements")} items={audit.offscreen} />
          <DiagnosticGroup title={t("Overflow candidates")} items={audit.overflowed} />
          <DiagnosticGroup title={t("Clipped text candidates")} items={audit.clipped} />
        </div>
      )}
    </div>
  );
}

export function DiagnosticGroup({ title, items }: DiagnosticGroupProps) {
  if (!items.length) return null;
  return (
    <div className="diagnostic-group">
      <strong>{title}</strong>
      {items.map((item, index) => (
        <div className="diagnostic-item" key={`${item.selector}-${index}`}>
          <code>{item.selector}</code>
          <span>{item.text || item.reason}</span>
        </div>
      ))}
    </div>
  );
}

function diffTone(changeType: unknown): string {
  const value = String(changeType || "").toLowerCase();
  if (value === "added") return "green";
  if (value === "removed") return "red";
  return "amber";
}

function draftStatusTone(status: unknown): string {
  const value = String(status || "").toLowerCase();
  if (value === "approved") return "green";
  if (value === "rejected" || value === "discarded") return "red";
  if (value === "draft") return "amber";
  return "muted";
}

function formatPreviewValue(value: unknown): string {
  if (value === undefined || value === null) {
    return "null";
  }
  const text = typeof value === "string" ? value : JSON.stringify(value);
  if (!text) {
    return "";
  }
  return text.length > 180 ? `${text.slice(0, 177)}...` : text;
}

function diagnosticTone(status: unknown): string {
  const value = String(status || "").toLowerCase();
  if (value === "pass" || value === "ready") return "green";
  if (value === "fail" || value === "action_required" || value === "failed") return "red";
  if (value === "warn" || value === "warning") return "amber";
  return "muted";
}

function policyTone(policy: unknown): string {
  const value = String(policy || "").toUpperCase();
  if (value === "AUTO_SAFE") return "green";
  if (value === "APPROVAL_REQUIRED" || value === "GITOPS_PR_ONLY") return "amber";
  if (value === "NEVER_AUTO_EXECUTE") return "red";
  return "muted";
}

function shortChecksum(value: string): string {
  return value.length > 18 ? `${value.slice(0, 12)}...${value.slice(-6)}` : value || "n/a";
}

function stringValue(value: unknown): string {
  return typeof value === "string" ? value.trim() : "";
}

function numberValue(value: unknown, fallback: number): number {
  const number = Number(value);
  return Number.isFinite(number) ? number : fallback;
}

function notificationDetails(event: AuditEventView): Record<string, unknown> {
  return event.details && typeof event.details === "object" && !Array.isArray(event.details)
    ? event.details as Record<string, unknown>
    : {};
}

function notificationChannels(event: AuditEventView): string {
  const details = notificationDetails(event);
  if (typeof details.channel === "string" && details.channel.trim()) {
    return details.channel;
  }
  if (Array.isArray(details.results)) {
    const channels = details.results
      .map((item) => item && typeof item === "object" && "channel" in item ? String(item.channel) : "")
      .filter(Boolean);
    return channels.length ? channels.join(", ") : "-";
  }
  return "-";
}

function notificationAttempts(event: AuditEventView): string {
  const details = notificationDetails(event);
  if (details.attempts !== undefined) {
    return String(details.attempts);
  }
  if (details.attempt !== undefined) {
    return String(details.attempt);
  }
  if (Array.isArray(details.results)) {
    const attempts = details.results
      .map((item) => item && typeof item === "object" && "attempts" in item ? String(item.attempts) : "")
      .filter(Boolean);
    return attempts.length ? attempts.join(", ") : "-";
  }
  return "-";
}

function notificationStatusCode(event: AuditEventView): string {
  const details = notificationDetails(event);
  if (details.status_code !== undefined && details.status_code !== "") {
    return String(details.status_code);
  }
  if (Array.isArray(details.results)) {
    const statusCodes = details.results
      .map((item) => item && typeof item === "object" && "status_code" in item ? String(item.status_code || "") : "")
      .filter(Boolean);
    return statusCodes.length ? statusCodes.join(", ") : "-";
  }
  return "-";
}

function notificationSummary(event: AuditEventView): string {
  const details = notificationDetails(event);
  if (typeof details.message === "string" && details.message.trim()) {
    return details.message;
  }
  if (typeof details.error === "string" && details.error.trim()) {
    return details.error;
  }
  return event.resource_id || "-";
}
