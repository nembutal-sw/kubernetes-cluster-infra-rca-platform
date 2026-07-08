import { PageHeader } from "../components/common";
import type { Locale } from "../constants";
import {
  CredentialWarning,
  CatalogSection,
  LayoutDiagnosticsSection,
  LlmConfigurationSection,
  NotificationDeliverySection,
  PlatformInfoSection,
  PreferenceAndCredentialPanels,
} from "../features/settings/SettingsPanels";
import type {
  AuditEventView,
  CatalogOverrideDraft,
  CatalogOverrideHandoff,
  LlmDiagnosticResponse,
  LlmSetupGuideResponse,
  LlmTestResponse,
  LoginIdChangeForm,
  NotificationTestResponse,
  OperationalCatalogDetail,
  CatalogOverridePreviewResponse,
  PasswordChangeForm,
  PlatformInfo,
  TFunction,
  UserAccount,
} from "../types";

interface SettingsViewProps {
  locale: Locale;
  setLocale: (locale: Locale) => void;
  platformInfo: PlatformInfo | null;
  catalogDetail: OperationalCatalogDetail | null;
  catalogOverrideDrafts: CatalogOverrideDraft[];
  llmDiagnostics: LlmDiagnosticResponse | null;
  llmSetupGuide: LlmSetupGuideResponse | null;
  notificationHistory: AuditEventView[];
  currentUser: UserAccount | null;
  onChangeLoginId: (form: LoginIdChangeForm) => void | Promise<void>;
  onChangePassword: (form: PasswordChangeForm) => void | Promise<void>;
  onTestNotification: () => NotificationTestResponse | Promise<NotificationTestResponse>;
  onTestLlm: () => LlmTestResponse | Promise<LlmTestResponse>;
  onPreviewCatalogOverride: (overrideJson: string, reason: string) => Promise<CatalogOverridePreviewResponse>;
  onCreateCatalogOverrideDraft: (overrideJson: string, reason: string) => Promise<CatalogOverrideDraft>;
  onDecideCatalogOverrideDraft: (
    draft: CatalogOverrideDraft,
    decision: "approve" | "reject" | "discard",
    note: string,
  ) => Promise<CatalogOverrideDraft>;
  onLoadCatalogOverrideHandoff: (draft: CatalogOverrideDraft) => Promise<CatalogOverrideHandoff>;
  t: TFunction;
}

export function SettingsView({
  locale,
  setLocale,
  platformInfo,
  catalogDetail,
  catalogOverrideDrafts,
  llmDiagnostics,
  llmSetupGuide,
  notificationHistory,
  currentUser,
  onChangeLoginId,
  onChangePassword,
  onTestNotification,
  onTestLlm,
  onPreviewCatalogOverride,
  onCreateCatalogOverrideDraft,
  onDecideCatalogOverrideDraft,
  onLoadCatalogOverrideHandoff,
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
      <CatalogSection
        catalogDetail={catalogDetail}
        platformInfo={platformInfo}
        catalogOverrideDrafts={catalogOverrideDrafts}
        currentUser={currentUser}
        onPreviewCatalogOverride={onPreviewCatalogOverride}
        onCreateCatalogOverrideDraft={onCreateCatalogOverrideDraft}
        onDecideCatalogOverrideDraft={onDecideCatalogOverrideDraft}
        onLoadCatalogOverrideHandoff={onLoadCatalogOverrideHandoff}
        t={t}
      />
      <PlatformInfoSection platformInfo={platformInfo} t={t} />
    </div>
  );
}
