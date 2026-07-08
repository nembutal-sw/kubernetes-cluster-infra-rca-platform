import type { FormEvent, ReactNode } from "react";
import { useState } from "react";

import { POLICY_HELP } from "../constants";
import type { Locale, NavItem } from "../constants";

import { policyTone, statusTone } from "../lib/consoleUtils";
import type { ClusterView, RcaReport, RecommendedAction, TFunction, UserAccount } from "../types";

type LocaleSetter = (locale: Locale) => void;
type MaybePromise<T = void> = T | Promise<T>;

interface ToastState {
  tone?: string;
  message: string;
}

interface LoginForm {
  username: string;
  password: string;
}

interface BootScreenProps {
  t?: TFunction;
}

interface LoginPageProps {
  onLogin: (form: LoginForm) => MaybePromise;
  locale: Locale;
  setLocale: LocaleSetter;
  t: TFunction;
  toast?: ToastState | null;
}

interface SidebarProps {
  items: readonly NavItem[];
  activeView: string;
  setActiveView: (view: string) => void;
  t: TFunction;
}

interface TopbarProps {
  user: UserAccount;
  locale: Locale;
  setLocale: LocaleSetter;
  onRefresh: () => MaybePromise;
  onLogout: () => MaybePromise;
  loading?: boolean;
  t: TFunction;
}

interface ActionDialogState {
  report: RcaReport;
  action: RecommendedAction;
  index: number;
}

interface ActionDialogProps {
  state: ActionDialogState;
  onClose: () => void;
  onConfirm: (report: RcaReport, index: number, note: string) => MaybePromise;
  t: TFunction;
}

interface DeleteClusterDialogProps {
  state: { cluster: ClusterView };
  onClose: () => void;
  onConfirm: (cluster: ClusterView, confirmName: string) => MaybePromise;
  t: TFunction;
}

interface SurfaceProps {
  title: ReactNode;
  subtitle?: ReactNode;
  action?: ReactNode;
  children: ReactNode;
}

interface PageHeaderProps {
  title: ReactNode;
  subtitle?: ReactNode;
  actions?: ReactNode;
}

interface MetricTileProps {
  label: ReactNode;
  value: ReactNode;
  tone?: string;
  icon: string;
}

interface ResponsiveTableProps {
  columns: string[];
  rows: ReactNode[][];
  empty: string;
}

interface StatusBadgeProps {
  value?: unknown;
  tone?: string;
  t?: TFunction;
}

interface EmptyStateProps {
  message: string;
}

interface LanguageSwitchProps {
  locale: Locale;
  setLocale: LocaleSetter;
  expanded?: boolean;
}

interface ToastProps {
  tone?: string;
  message: string;
}

interface IconProps {
  name: string;
}

export function BootScreen({ t = (x) => x }: BootScreenProps) {
  return (
    <div className="boot-screen">
      <div className="boot-mark">
        <Icon name="activity" />
      </div>
      <div>
        <strong>Cluster RCA Console</strong>
        <p>{t("Loading console")}</p>
      </div>
    </div>
  );
}

export function LoginPage({ onLogin, locale, setLocale, t, toast }: LoginPageProps) {
  const [form, setForm] = useState({ username: "admin", password: "" });
  const [busy, setBusy] = useState(false);
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setBusy(true);
    try {
      await onLogin(form);
    } finally {
      setBusy(false);
    }
  }
  return (
    <div className="login-screen">
      <section className="login-panel" aria-label={t("Cluster RCA Console")}>
        <div className="brand-row">
          <div className="brand-icon"><Icon name="activity" /></div>
          <div>
            <h1>{t("Cluster RCA Console")}</h1>
            <p>{t("Linux and Kubernetes infrastructure root cause console")}</p>
          </div>
        </div>
        <div className="login-observability">
          <div><span>{t("Disk I/O")}</span><strong>{t("watch")}</strong></div>
          <div><span>{t("Kubelet")}</span><strong>{t("rule gate")}</strong></div>
          <div><span>{t("Network")}</span><strong>{t("timeline")}</strong></div>
        </div>
        <form onSubmit={submit} className="login-form">
          <label>
            {t("Username")}
            <input
              className="form-control"
              autoComplete="username"
              value={form.username}
              onChange={(event) => setForm({ ...form, username: event.target.value })}
            />
          </label>
          <label>
            {t("Password")}
            <input
              className="form-control"
              type="password"
              autoComplete="current-password"
              value={form.password}
              onChange={(event) => setForm({ ...form, password: event.target.value })}
            />
          </label>
          <button className="btn btn-primary w-100" disabled={busy}>
            {busy ? "..." : t("Sign in")}
          </button>
        </form>
        <div className="login-footer">
          <span>{t("Initial account")}: admin</span>
          <LanguageSwitch locale={locale} setLocale={setLocale} />
        </div>
      </section>
      <aside className="login-intel-panel" aria-label={t("Operations readiness")}>
        <div className="intel-panel-head">
          <span>{t("Operations Console")}</span>
          <strong>{t("Policy gate")}</strong>
        </div>
        <div className="intel-map" aria-hidden="true">
          <span className="intel-node active" />
          <span className="intel-line" />
          <span className="intel-node warn" />
          <span className="intel-line" />
          <span className="intel-node" />
        </div>
        <div className="intel-signal-list">
          <div>
            <span>{t("Agent fleet")}</span>
            <strong>{t("Healthy agents")}</strong>
          </div>
          <div>
            <span>{t("Analysis pipeline")}</span>
            <strong>{t("active tasks")}</strong>
          </div>
          <div>
            <span>{t("Policy queue")}</span>
            <strong>{t("approvals pending")}</strong>
          </div>
        </div>
      </aside>
      {toast && <Toast tone={toast.tone} message={toast.message} />}
    </div>
  );
}

export function Sidebar({ items, activeView, setActiveView, t }: SidebarProps) {
  return (
    <aside className="console-sidebar">
      <div className="sidebar-brand">
        <div className="brand-icon"><Icon name="activity" /></div>
        <div>
          <strong>Cluster RCA</strong>
          <span>Console</span>
        </div>
      </div>
      <nav className="sidebar-nav" aria-label={t("Console navigation")}>
        {items.map((item) => (
          <button
            key={item.id}
            type="button"
            data-testid={`nav-${item.id}`}
            className={item.id === activeView ? "active" : ""}
            onClick={() => setActiveView(item.id)}
          >
            <Icon name={item.icon} />
            <span>{t(item.label)}</span>
          </button>
        ))}
      </nav>
    </aside>
  );
}

export function Topbar({ user, locale, setLocale, onRefresh, onLogout, loading, t }: TopbarProps) {
  return (
    <header className="console-topbar">
      <div>
        <div className="topbar-eyebrow">Cluster RCA Console</div>
        <h2>{t("Linux and Kubernetes infrastructure root cause console")}</h2>
      </div>
      <div className="topbar-actions">
        <div className="ops-live-pill">
          <span />
          <strong>{t("Ops live")}</strong>
        </div>
        <LanguageSwitch locale={locale} setLocale={setLocale} />
        <button className="btn btn-outline-secondary btn-sm icon-button" onClick={onRefresh} disabled={loading}>
          <Icon name={loading ? "arrow-repeat" : "arrow-clockwise"} />
          <span>{t("Refresh")}</span>
        </button>
        <div className="user-chip">
          <Icon name="person-circle" />
          <span>{user.email || user.user_id}</span>
          <StatusBadge value={user.role} />
        </div>
        <button className="btn btn-dark btn-sm icon-button" onClick={onLogout}>
          <Icon name="box-arrow-right" />
          <span>{t("Logout")}</span>
        </button>
      </div>
    </header>
  );
}

export function ActionDialog({ state, onClose, onConfirm, t }: ActionDialogProps) {
  const [note, setNote] = useState("");
  const [busy, setBusy] = useState(false);
  const { report, action, index } = state;
  const commandPreview = action.execution_plan?.command_preview || [];
  async function submit() {
    setBusy(true);
    try {
      await onConfirm(report, index, note);
    } finally {
      setBusy(false);
    }
  }
  return (
    <div className="modal-backdrop-custom">
      <section className="console-modal">
        <header>
          <div>
            <p className="section-kicker">{t("Policy gate")}</p>
            <h2>{action.automation_allowed ? t("Collect evidence") : t("Request action")}</h2>
          </div>
          <button className="btn btn-sm btn-outline-secondary" onClick={onClose}><Icon name="x-lg" /></button>
        </header>
        <div className="policy-warning">
          <Icon name="shield-lock" />
          <span>{action.source === "llm" ? t("LLM diagnostic only") : t(POLICY_HELP[action.policy || ""] || "Policy controlled workflow.")}</span>
        </div>
        <div className="action-card blocked">
          <div className="action-head">
            <StatusBadge value={action.policy} tone={policyTone(action.policy)} t={t} />
            <StatusBadge value={action.automation_allowed ? "automation_allowed" : "automation_allowed=false"} tone={action.automation_allowed ? "green" : "amber"} />
          </div>
          <h3>{action.action}</h3>
          <p>{action.reason}</p>
          {commandPreview.length > 0 && <pre className="command-preview">{commandPreview.join("\n")}</pre>}
        </div>
        <textarea className="form-control" rows={3} placeholder={t("Operator note")} value={note} onChange={(event) => setNote(event.target.value)} />
        <footer>
          <button className="btn btn-outline-secondary" onClick={onClose}>{t("Cancel")}</button>
          <button className="btn btn-primary" onClick={submit} disabled={busy}>{busy ? "..." : t("Confirm")}</button>
        </footer>
      </section>
    </div>
  );
}

export function DeleteClusterDialog({ state, onClose, onConfirm, t }: DeleteClusterDialogProps) {
  const [confirmName, setConfirmName] = useState("");
  const cluster = state.cluster;
  return (
    <div className="modal-backdrop-custom">
      <section className="console-modal">
        <header>
          <div>
            <p className="section-kicker">{cluster.cluster_id}</p>
            <h2>{t("Delete cluster")}</h2>
          </div>
          <button className="btn btn-sm btn-outline-secondary" onClick={onClose}><Icon name="x-lg" /></button>
        </header>
        <div className="alert alert-danger">
          {t("Type the cluster name to confirm deletion.")} <strong>{cluster.name}</strong>
        </div>
        <input className="form-control" value={confirmName} onChange={(event) => setConfirmName(event.target.value)} />
        <footer>
          <button className="btn btn-outline-secondary" onClick={onClose}>{t("Cancel")}</button>
          <button className="btn btn-danger" disabled={confirmName !== cluster.name} onClick={() => onConfirm(cluster, confirmName)}>{t("Delete")}</button>
        </footer>
      </section>
    </div>
  );
}

export function Surface({ title, subtitle, action, children }: SurfaceProps) {
  return (
    <section className="surface">
      <header className="surface-head">
        <div>
          <h2>{title}</h2>
          {subtitle && <p>{subtitle}</p>}
        </div>
        {action}
      </header>
      {children}
    </section>
  );
}

export function PageHeader({ title, subtitle, actions }: PageHeaderProps) {
  return (
    <div className="page-header">
      <div>
        <p className="section-kicker">Operations Console</p>
        <h1>{title}</h1>
        <p>{subtitle}</p>
      </div>
      {actions && <div className="page-actions">{actions}</div>}
    </div>
  );
}

export function MetricTile({ label, value, tone = "blue", icon }: MetricTileProps) {
  return (
    <article className={`metric-tile ${tone}`}>
      <div className="metric-icon"><Icon name={icon} /></div>
      <span>{label}</span>
      <strong>{value}</strong>
    </article>
  );
}

export function ResponsiveTable({ columns, rows, empty }: ResponsiveTableProps) {
  if (!rows.length) return <EmptyState message={empty} />;
  return (
    <div className="table-responsive console-table-wrap">
      <table className="table console-table align-middle">
        <thead><tr>{columns.map((column) => <th key={column}>{column}</th>)}</tr></thead>
        <tbody>
          {rows.map((row, index) => (
            <tr key={index}>{row.map((cell, cellIndex) => <td key={cellIndex}>{cell}</td>)}</tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

export function StatusBadge({ value, tone, t = (x) => x }: StatusBadgeProps) {
  return <span className={`status-badge ${tone || statusTone(value)}`}>{t(String(value || "n/a"))}</span>;
}

export function EmptyState({ message }: EmptyStateProps) {
  return (
    <div className="empty-state">
      <Icon name="inbox" />
      <span>{message}</span>
    </div>
  );
}

export function LanguageSwitch({ locale, setLocale, expanded = false }: LanguageSwitchProps) {
  return (
    <div className={`language-switch ${expanded ? "expanded" : ""}`}>
      <button className={locale === "en" ? "active" : ""} onClick={() => setLocale("en")} type="button">EN</button>
      <button className={locale === "ko" ? "active" : ""} onClick={() => setLocale("ko")} type="button">KO</button>
    </div>
  );
}

export function Toast({ tone, message }: ToastProps) {
  return <div className={`console-toast ${tone || "success"}`}>{message}</div>;
}

export function Icon({ name }: IconProps) {
  return <i className={`bi bi-${name}`} aria-hidden="true" />;
}
