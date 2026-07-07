import { PageHeader } from "../components/common";
import type { Locale } from "../constants";
import {
  CredentialWarning,
  LayoutDiagnosticsSection,
  LlmConfigurationSection,
  NotificationDeliverySection,
  PlatformInfoSection,
  PreferenceAndCredentialPanels,
} from "../features/settings/SettingsPanels";
import type {
  AuditEventView,
  LlmDiagnosticResponse,
  LlmSetupGuideResponse,
  LlmTestResponse,
  LoginIdChangeForm,
  NotificationTestResponse,
  PasswordChangeForm,
  PlatformInfo,
  TFunction,
  UserAccount,
} from "../types";

interface SettingsViewProps {
  locale: Locale;
  setLocale: (locale: Locale) => void;
  platformInfo: PlatformInfo | null;
  llmDiagnostics: LlmDiagnosticResponse | null;
  llmSetupGuide: LlmSetupGuideResponse | null;
  notificationHistory: AuditEventView[];
  currentUser: UserAccount | null;
  onChangeLoginId: (form: LoginIdChangeForm) => void | Promise<void>;
  onChangePassword: (form: PasswordChangeForm) => void | Promise<void>;
  onTestNotification: () => NotificationTestResponse | Promise<NotificationTestResponse>;
  onTestLlm: () => LlmTestResponse | Promise<LlmTestResponse>;
  t: TFunction;
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
  return (
    <div className="page-stack">
      <PageHeader title={t("Settings")} subtitle={t("Console preferences and local admin credential rotation.")} />
      <CredentialWarning currentUser={currentUser} t={t} />
      <PreferenceAndCredentialPanels
        locale={locale}
        setLocale={setLocale}
        currentUser={currentUser}
        onChangeLoginId={onChangeLoginId}
        onChangePassword={onChangePassword}
        t={t}
      />
      <LayoutDiagnosticsSection t={t} />
      <LlmConfigurationSection
        platformInfo={platformInfo}
        llmDiagnostics={llmDiagnostics}
        llmSetupGuide={llmSetupGuide}
        currentUser={currentUser}
        onTestLlm={onTestLlm}
        t={t}
      />
      <NotificationDeliverySection
        platformInfo={platformInfo}
        notificationHistory={notificationHistory}
        currentUser={currentUser}
        onTestNotification={onTestNotification}
        t={t}
      />
      <PlatformInfoSection platformInfo={platformInfo} t={t} />
    </div>
  );
}
