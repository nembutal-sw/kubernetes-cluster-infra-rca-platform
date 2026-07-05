import type { FormEvent, ReactNode } from "react";
import { useEffect, useState } from "react";

import {
  EmptyState,
  Icon,
  LanguageSwitch,
  PageHeader,
  Surface,
} from "../components/common";
import type { Locale } from "../constants";
import { formatDate, platformInfoRows, runConsoleLayoutAudit } from "../lib/consoleUtils";
import type { PlatformInfo, TFunction, UserAccount } from "../types";

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
  currentUser: UserAccount | null;
  onChangeLoginId: (form: LoginIdForm) => void | Promise<void>;
  onChangePassword: (form: PasswordForm) => void | Promise<void>;
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

export function SettingsView({
  locale,
  setLocale,
  platformInfo,
  currentUser,
  onChangeLoginId,
  onChangePassword,
  t,
}: SettingsViewProps) {
  const [loginId, setLoginId] = useState({
    current_password: "",
    new_username: currentUser?.email || "",
  });
  const [password, setPassword] = useState({ current_password: "", new_password: "" });
  const [layoutAudit, setLayoutAudit] = useState<LayoutAudit | null>(null);
  const defaultCredentialVisible = currentUser?.email === "admin";
  const infoRows = platformInfoRows(platformInfo, t) as PlatformInfoRow[];

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
