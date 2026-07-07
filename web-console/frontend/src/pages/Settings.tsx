import type { FormEvent, ReactNode } from "react";
import { useEffect, useState } from "react";

import {
  EmptyState,
  Icon,
  LanguageSwitch,
  PageHeader,
  StatusBadge,
  Surface,
} from "../components/common";
import type { Locale } from "../constants";
import { formatDate, platformInfoRows, runConsoleLayoutAudit } from "../lib/consoleUtils";
import type {
  AuditEventView,
  LlmConfigurationInfo,
  LlmDiagnosticCheck,
  LlmDiagnosticResponse,
  LlmProviderSetupOption,
  LlmSetupGuideResponse,
  LlmTestResponse,
  NotificationConfigurationInfo,
  NotificationTestResponse,
  PlatformInfo,
  TFunction,
  UserAccount,
} from "../types";

interface LoginIdForm {
  current_password: string;
  new_username: string;
}

interface PasswordForm {
  current_password: string;
  new_password: string;
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

interface SettingsViewProps {
  locale: Locale;
  setLocale: (locale: Locale) => void;
  platformInfo: PlatformInfo | null;
  llmDiagnostics: LlmDiagnosticResponse | null;
  llmSetupGuide: LlmSetupGuideResponse | null;
  notificationHistory: AuditEventView[];
  currentUser: UserAccount | null;
  onChangeLoginId: (form: LoginIdForm) => void | Promise<void>;
  onChangePassword: (form: PasswordForm) => void | Promise<void>;
  onTestNotification: () => NotificationTestResponse | Promise<NotificationTestResponse>;
  onTestLlm: () => LlmTestResponse | Promise<LlmTestResponse>;
  t: TFunction;
}

interface LayoutAuditPanelProps {
  audit: LayoutAudit | null;
  t: TFunction;
}

interface DiagnosticGroupProps {
  title: string;
  items: LayoutIssue[];
}

interface PlatformInfoRow {
  key: string;
  label: ReactNode;
  value: ReactNode;
  tone?: string;
}

interface LlmInfoRow {
  key: string;
  label: ReactNode;
  value: ReactNode;
  tone?: string;
}

export function SettingsView({
  locale,
  setLocale,
  platformInfo,
  llmDiagnostics,
  llmSetupGuide,
  notificationHistory,
  currentUser,
  onChangeLoginId,
  onChangePassword,
  onTestNotification,
  onTestLlm,
  t,
}: SettingsViewProps) {
  const [loginId, setLoginId] = useState({
    current_password: "",
    new_username: currentUser?.email || "",
  });
  const [password, setPassword] = useState({ current_password: "", new_password: "" });
  const [layoutAudit, setLayoutAudit] = useState<LayoutAudit | null>(null);
  const [testingNotification, setTestingNotification] = useState(false);
  const [testingLlm, setTestingLlm] = useState(false);
  const defaultCredentialVisible = currentUser?.email === "admin";
  const infoRows = platformInfoRows(platformInfo, t) as PlatformInfoRow[];
  const llmRows = buildLlmRows(platformInfo?.llm, t);
  const notificationRows = buildNotificationRows(platformInfo?.notification, t);
  const canTestNotification = ["admin", "operator"].includes(String(currentUser?.role || ""));
  const canTestLlm = ["admin", "operator"].includes(String(currentUser?.role || ""));
  const canViewNotificationHistory = ["admin", "auditor"].includes(String(currentUser?.role || ""));

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
    <div className="page-stack">
      <PageHeader title={t("Settings")} subtitle={t("Console preferences and local admin credential rotation.")} />
      {defaultCredentialVisible && (
        <div className="credential-warning">
          <Icon name="shield-lock" />
          <div>
            <strong>{t("Default admin account is active")}</strong>
            <span>{t("Change the login ID and password after the first sign-in.")}</span>
          </div>
        </div>
      )}
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
      <Surface
        title={t("Console diagnostics")}
        subtitle={t("Run a client-side layout check on the current console view")}
        action={<button className="btn btn-sm btn-outline-secondary icon-button" onClick={() => setLayoutAudit(runConsoleLayoutAudit())}><Icon name="display" /><span>{t("Run layout check")}</span></button>}
      >
        <LayoutAuditPanel audit={layoutAudit} t={t} />
      </Surface>
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
        <div className="info-grid">
          {llmRows.map((row) => (
            <div key={row.key} className={row.tone ? `info-card-${row.tone}` : ""}>
              <span>{row.label}</span>
              <strong>{row.value}</strong>
            </div>
          ))}
        </div>
        <LlmDiagnosticsPanel diagnostics={llmDiagnostics} t={t} />
        <LlmSetupGuidePanel guide={llmSetupGuide} t={t} />
      </Surface>
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
        <div className="info-grid">
          {notificationRows.map((row) => (
            <div key={row.key} className={row.tone ? `info-card-${row.tone}` : ""}>
              <span>{row.label}</span>
              <strong>{row.value}</strong>
            </div>
          ))}
        </div>
        <NotificationHistory
          events={notificationHistory}
          canView={canViewNotificationHistory}
          t={t}
        />
      </Surface>
      <Surface title={t("Platform info")} subtitle={t("Protocol compatibility and export integrity")}>
        <div className="info-grid">
          {infoRows.map((row) => (
            <div key={row.key} className={row.tone ? `info-card-${row.tone}` : ""}>
              <span>{row.label}</span>
              <strong>{row.value}</strong>
            </div>
          ))}
        </div>
      </Surface>
    </div>
  );
}

function buildNotificationRows(
  notification: NotificationConfigurationInfo | undefined,
  t: TFunction,
): LlmInfoRow[] {
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

function buildLlmRows(llm: LlmConfigurationInfo | undefined, t: TFunction): LlmInfoRow[] {
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

function diagnosticTone(status: unknown): string {
  const value = String(status || "").toLowerCase();
  if (value === "pass" || value === "ready") return "green";
  if (value === "fail" || value === "action_required" || value === "failed") return "red";
  if (value === "warn" || value === "warning") return "amber";
  return "muted";
}

function stringValue(value: unknown): string {
  return typeof value === "string" ? value.trim() : "";
}

function numberValue(value: unknown, fallback: number): number {
  const number = Number(value);
  return Number.isFinite(number) ? number : fallback;
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
