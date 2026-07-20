import { useCallback } from "react";
import type { Dispatch, SetStateAction } from "react";

import { sortByTime } from "../lib/consoleUtils";
import type {
  ApiCall,
  AuditEventView,
  AuthSession,
  CatalogOverrideDraft,
  CatalogOverrideHandoff,
  CatalogOverridePreviewResponse,
  GitOpsChange,
  GitOpsDeploymentState,
  LlmDiagnosticResponse,
  LlmTestResponse,
  LoginIdChangeForm,
  NotificationTestResponse,
  NotifyFunction,
  PasswordChangeForm,
  TFunction,
  UserAccount,
} from "../types";

interface SettingsOperationsOptions {
  callApi: ApiCall;
  currentUser: UserAccount | null;
  setCurrentUser: (user: UserAccount | null) => void;
  setSession: Dispatch<SetStateAction<AuthSession | null>>;
  setNotificationHistory: (events: AuditEventView[]) => void;
  setAuditEvents: (events: AuditEventView[]) => void;
  setCatalogOverrideDrafts: (drafts: CatalogOverrideDraft[]) => void;
  setLlmDiagnostics: (diagnostics: LlmDiagnosticResponse | null) => void;
  notify: NotifyFunction;
  t: TFunction;
}

function hasAuditAccess(role?: string): boolean {
  return role === "admin" || role === "auditor";
}

function hasCatalogDraftAccess(role?: string): boolean {
  return ["admin", "operator", "approver", "auditor"].includes(String(role || ""));
}

export function useSettingsOperations(options: SettingsOperationsOptions) {
  const {
    callApi,
    currentUser,
    setCurrentUser,
    setSession,
    setNotificationHistory,
    setAuditEvents,
    setCatalogOverrideDrafts,
    setLlmDiagnostics,
    notify,
    t,
  } = options;

  const refreshAudit = useCallback(async () => {
    if (!hasAuditAccess(currentUser?.role)) return;
    const events = await callApi<AuditEventView[]>("/api/audit/events?limit=200");
    setAuditEvents(sortByTime(Array.isArray(events) ? events : [], "created_at"));
  }, [callApi, currentUser?.role, setAuditEvents]);

  const reloadCatalogOverrideDrafts = useCallback(async () => {
    if (!hasCatalogDraftAccess(currentUser?.role)) {
      setCatalogOverrideDrafts([]);
      return;
    }
    const drafts = await callApi<CatalogOverrideDraft[]>("/api/v1/catalog/overrides/drafts?limit=50");
    setCatalogOverrideDrafts(sortByTime(Array.isArray(drafts) ? drafts : [], "created_at"));
  }, [callApi, currentUser?.role, setCatalogOverrideDrafts]);

  const changePassword = useCallback(async (form: PasswordChangeForm) => {
    await callApi("/api/auth/change-password", {
      method: "POST",
      body: { current_password: form.current_password, new_password: form.new_password },
    });
    notify(t("Password changed."));
  }, [callApi, notify, t]);

  const changeLoginId = useCallback(async (form: LoginIdChangeForm) => {
    const updatedUser = await callApi<UserAccount>("/api/auth/change-login-id", {
      method: "POST",
      body: { current_password: form.current_password, new_username: form.new_username },
    });
    setCurrentUser(updatedUser);
    setSession((value) => value ? { ...value, user: updatedUser } : { user: updatedUser });
    notify(t("Login ID changed."));
  }, [callApi, notify, setCurrentUser, setSession, t]);

  const testNotificationDelivery = useCallback(async (): Promise<NotificationTestResponse> => {
    const response = await callApi<NotificationTestResponse>("/api/notifications/test", {
      method: "POST",
      body: { confirmed: true },
    });
    if (response.outcome === "success") {
      notify(t("Notification test delivered."));
    } else if (response.outcome === "skipped") {
      notify(response.message || t("Notification test skipped."), "warning");
    } else {
      notify(response.message || t("Notification test failed."), response.outcome === "partial" ? "warning" : "danger");
    }
    if (hasAuditAccess(currentUser?.role)) {
      const history = await callApi<AuditEventView[]>("/api/notifications/history?limit=50");
      setNotificationHistory(sortByTime(Array.isArray(history) ? history : [], "created_at"));
    }
    return response;
  }, [callApi, currentUser?.role, notify, setNotificationHistory, t]);

  const testLlmConnection = useCallback(async (): Promise<LlmTestResponse> => {
    const response = await callApi<LlmTestResponse>("/api/llm/test", {
      method: "POST",
      body: { confirmed: true },
    });
    if (response.outcome === "completed") {
      notify(t("LLM test completed."));
    } else if (response.outcome === "skipped") {
      notify(t(String(response.message || "LLM test skipped.")), "warning");
    } else {
      notify(t(String(response.message || "LLM test failed.")), "danger");
    }
    setLlmDiagnostics(await callApi<LlmDiagnosticResponse>("/api/llm/diagnostics"));
    await refreshAudit();
    return response;
  }, [callApi, notify, refreshAudit, setLlmDiagnostics, t]);

  const previewCatalogOverride = useCallback(async (
    overrideJson: string,
    reason: string,
  ): Promise<CatalogOverridePreviewResponse> => {
    const response = await callApi<CatalogOverridePreviewResponse>("/api/v1/catalog/preview", {
      method: "POST",
      body: { override_json: overrideJson, reason },
    });
    notify(
      response.valid ? t("Catalog override preview completed.") : t("Catalog override preview rejected."),
      response.valid ? "success" : "warning",
    );
    await refreshAudit();
    return response;
  }, [callApi, notify, refreshAudit, t]);

  const createCatalogOverrideDraft = useCallback(async (
    overrideJson: string,
    reason: string,
  ): Promise<CatalogOverrideDraft> => {
    const draft = await callApi<CatalogOverrideDraft>("/api/v1/catalog/overrides/drafts", {
      method: "POST",
      body: { override_json: overrideJson, reason },
    });
    notify(t("Catalog override draft saved."));
    await Promise.all([reloadCatalogOverrideDrafts(), refreshAudit()]);
    return draft;
  }, [callApi, notify, refreshAudit, reloadCatalogOverrideDrafts, t]);

  const decideCatalogOverrideDraft = useCallback(async (
    draft: CatalogOverrideDraft,
    decision: "approve" | "reject" | "discard",
    note: string,
  ): Promise<CatalogOverrideDraft> => {
    const updated = await callApi<CatalogOverrideDraft>(
      `/api/v1/catalog/overrides/drafts/${encodeURIComponent(draft.draft_id)}/${decision}`,
      { method: "POST", body: { confirmed: true, note } },
    );
    const decisionMessage = decision === "approve"
      ? "Catalog override draft approved."
      : decision === "reject"
        ? "Catalog override draft rejected."
        : "Catalog override draft discarded.";
    notify(t(decisionMessage));
    await Promise.all([reloadCatalogOverrideDrafts(), refreshAudit()]);
    return updated;
  }, [callApi, notify, refreshAudit, reloadCatalogOverrideDrafts, t]);

  const loadCatalogOverrideHandoff = useCallback((draft: CatalogOverrideDraft): Promise<CatalogOverrideHandoff> => (
    callApi<CatalogOverrideHandoff>(
      `/api/v1/catalog/overrides/drafts/${encodeURIComponent(draft.draft_id)}/handoff`,
    )
  ), [callApi]);

  const createCatalogGitOpsChange = useCallback(async (draft: CatalogOverrideDraft): Promise<GitOpsChange> => {
    const change = await callApi<GitOpsChange>(
      `/api/v1/catalog/overrides/drafts/${encodeURIComponent(draft.draft_id)}/gitops-changes`,
      { method: "POST", body: { confirmed: true } },
    );
    const failed = change.pull_request_state === "failed";
    notify(t(failed ? "GitOps change request creation failed." : "GitOps change request created."), failed ? "danger" : "success");
    return change;
  }, [callApi, notify, t]);

  const loadCatalogGitOpsChanges = useCallback(async (draft: CatalogOverrideDraft): Promise<GitOpsChange[]> => {
    const query = new URLSearchParams({
      sourceType: "catalog_override_draft",
      sourceId: draft.draft_id,
      limit: "10",
    });
    const changes = await callApi<GitOpsChange[]>(`/api/v1/gitops/changes?${query.toString()}`);
    return Array.isArray(changes) ? changes : [];
  }, [callApi]);

  const updateGitOpsOutcome = useCallback(async (
    change: GitOpsChange,
    state: GitOpsDeploymentState,
    verificationResult: string,
    rollbackReference: string,
  ): Promise<GitOpsChange> => {
    const updated = await callApi<GitOpsChange>(
      `/api/v1/gitops/changes/${encodeURIComponent(change.change_id)}/outcome`,
      {
        method: "POST",
        body: {
          confirmed: true,
          deployment_state: state,
          verification_result: verificationResult,
          rollback_reference: rollbackReference,
        },
      },
    );
    notify(t("GitOps deployment state recorded."));
    return updated;
  }, [callApi, notify, t]);

  return {
    changePassword,
    changeLoginId,
    testNotificationDelivery,
    testLlmConnection,
    reloadCatalogOverrideDrafts,
    previewCatalogOverride,
    createCatalogOverrideDraft,
    decideCatalogOverrideDraft,
    loadCatalogOverrideHandoff,
    createCatalogGitOpsChange,
    loadCatalogGitOpsChanges,
    updateGitOpsOutcome,
  };
}
