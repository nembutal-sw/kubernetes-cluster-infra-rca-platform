import { useCallback, useEffect, useState } from "react";
import { createRoot } from "react-dom/client";
import { BrowserRouter, useLocation, useNavigate } from "react-router-dom";
import "bootstrap/dist/css/bootstrap.min.css";
import "bootstrap-icons/font/bootstrap-icons.css";
import "./styles.css";

import { requestCurrentUser } from "./api/client";
import { ActionDialog, BootScreen, DeleteClusterDialog, LoginPage, Sidebar, Toast, Topbar } from "./components/common";
import { DataStatusBanner } from "./components/DataStatusBanner";
import { RouteStatusNotice } from "./components/RouteStatusNotice";
import { NAV_ITEMS } from "./constants";
import { useAuthenticatedApi } from "./hooks/useAuthenticatedApi";
import { useClusterDetail } from "./hooks/useClusterDetail";
import { useConsoleData } from "./hooks/useConsoleData";
import { useConsoleLocale } from "./hooks/useConsoleLocale";
import { useReportDetail } from "./hooks/useReportDetail";
import { useToast } from "./hooks/useToast";
import { AuditView } from "./pages/Audit";
import { ClustersView } from "./pages/Clusters";
import { IncidentsView } from "./pages/Incidents";
import { OverviewView } from "./pages/Overview";
import { PipelineView } from "./pages/Pipeline";
import { ReportsView } from "./pages/Reports";
import { SettingsView } from "./pages/Settings";
import { WebhooksView } from "./pages/Webhooks";
import { buildAuditQuery, copyText, sortByTime } from "./lib/consoleUtils";
import { clusterPath, incidentPath, parseConsoleRoute, pathForView, reportPath } from "./routing";
import type {
  ActionDialogState,
  ActionRequestView,
  AgentTokenRotateResponse,
  AnalysisTaskView,
  AuditEventView,
  AuthSession,
  CatalogOverrideDraft,
  CatalogOverrideHandoff,
  CatalogOverridePreviewResponse,
  GitOpsChange,
  GitOpsDeploymentState,
  ClusterCreateForm,
  ClusterThresholdSettings,
  ClusterView,
  DeleteClusterDialogState,
  DemoScenarioView,
  IncidentView,
  LoginForm,
  LoginIdChangeForm,
  LlmDiagnosticResponse,
  LlmTestResponse,
  NotificationTestResponse,
  PasswordChangeForm,
  RcaReport,
  UserAccount,
} from "./types";

function ConsoleApp() {
  const { locale, setLocale, t } = useConsoleLocale();
  const { toast, notify } = useToast();
  const [session, setSession] = useState<AuthSession | null>(null);
  const [currentUser, setCurrentUser] = useState<UserAccount | null>(null);
  const [bootLoading, setBootLoading] = useState(true);
  const [actionDialog, setActionDialog] = useState<ActionDialogState | null>(null);
  const [deleteDialog, setDeleteDialog] = useState<DeleteClusterDialogState | null>(null);
  const location = useLocation();
  const navigate = useNavigate();
  const route = parseConsoleRoute(location.pathname);
  const requestedNav = NAV_ITEMS.find((item) => item.id === route.view);
  const routeAllowed = !currentUser || !requestedNav?.roles || requestedNav.roles.includes(currentUser.role);
  const activeView = routeAllowed ? route.view : "overview";

  const handleUnauthorized = useCallback(() => {
    setSession(null);
    setCurrentUser(null);
    notify(t("Your session expired. Sign in again."), "warning");
  }, [notify, t]);

  const { callApi, downloadApi } = useAuthenticatedApi(session, handleUnauthorized);
  const {
    loadingData,
    lastUpdatedAt,
    lastCompleteRefreshAt,
    loadStates,
    clusters,
    reports,
    incidents,
    analysisTasks,
    actionRequests,
    agentHealth,
    auditEvents,
    notificationHistory,
    catalogOverrideDrafts,
    demoScenarios,
    platformInfo,
    catalogDetail,
    llmDiagnostics,
    llmSetupGuide,
    setAuditEvents,
    setNotificationHistory,
    setCatalogOverrideDrafts,
    setLlmDiagnostics,
    setLlmSetupGuide,
    loadConsoleData,
  } = useConsoleData(callApi, currentUser);
  const {
    selectedCluster,
    setSelectedCluster,
    clusterDetail,
    setClusterDetail,
    installCommand,
    setInstallCommand,
    generateInstallCommand,
    loadClusterDetail,
    clearClusterDetail,
  } = useClusterDetail(callApi);
  const {
    selectedReportId,
    setSelectedReportId,
    reportDetail,
    setReportDetail,
    loadReportDetail,
  } = useReportDetail(callApi, currentUser, notify, t);

  useEffect(() => {
    let mounted = true;
    async function boot() {
      try {
        const user = await requestCurrentUser();
        if (!mounted) return;
        setCurrentUser(user);
        setSession({ user });
      } catch {
        if (mounted) setCurrentUser(null);
      } finally {
        if (mounted) setBootLoading(false);
      }
    }
    void boot();
    return () => {
      mounted = false;
    };
  }, []);

  useEffect(() => {
    if (!route.valid || location.pathname !== route.canonicalPath) {
      navigate(route.canonicalPath, { replace: true });
    }
  }, [location.pathname, navigate, route.canonicalPath, route.valid]);

  useEffect(() => {
    if (currentUser && !routeAllowed) {
      navigate("/overview", { replace: true });
    }
  }, [currentUser, navigate, routeAllowed]);

  useEffect(() => {
    if (currentUser) {
      void loadConsoleData(true);
    }
  }, [currentUser, loadConsoleData]);

  useEffect(() => {
    if (!currentUser) return undefined;
    const timer = window.setInterval(() => {
      if (document.visibilityState === "visible") {
        void loadConsoleData(true);
      }
    }, 30000);
    return () => window.clearInterval(timer);
  }, [currentUser, loadConsoleData]);

  useEffect(() => {
    if (!currentUser || activeView !== "reports") return;
    if (route.reportId) {
      if (selectedReportId !== route.reportId) setSelectedReportId(route.reportId);
      return;
    }
    if (reports.length) {
      navigate(reportPath(reports[0].report_id), { replace: true });
    } else if (loadStates.reports.loadedAt) {
      setSelectedReportId(null);
      setReportDetail(null);
    }
  }, [activeView, currentUser, loadStates.reports.loadedAt, navigate, reports, route.reportId, selectedReportId, setReportDetail, setSelectedReportId]);

  useEffect(() => {
    if (!currentUser || activeView !== "clusters") return;
    if (!route.clusterId) {
      if (selectedCluster) clearClusterDetail();
      return;
    }
    const cluster = clusters.find((item) => item.cluster_id === route.clusterId);
    if (cluster && selectedCluster?.cluster_id !== cluster.cluster_id) {
      void loadClusterDetail(cluster);
    }
  }, [activeView, clearClusterDetail, clusters, currentUser, loadClusterDetail, route.clusterId, selectedCluster]);

  const navigateToView = useCallback((view: string) => {
    navigate(pathForView(view));
  }, [navigate]);

  const openClusterDetail = useCallback(async (cluster: ClusterView | null) => {
    if (!cluster) return;
    navigate(clusterPath(cluster.cluster_id));
    await loadClusterDetail(cluster);
  }, [loadClusterDetail, navigate]);

  const openReport = useCallback((reportId: string) => {
    if (reportId) navigate(reportPath(reportId));
  }, [navigate]);

  const openIncident = useCallback((incidentId: string) => {
    if (incidentId) navigate(incidentPath(incidentId));
  }, [navigate]);

  async function login(form: LoginForm) {
    try {
      const nextSession = await callApi<AuthSession>("/api/auth/login", {
        method: "POST",
        body: form,
        handleUnauthorized: false,
      });
      setSession(nextSession);
      setCurrentUser(nextSession.user || null);
      notify(t("Signed in."));
    } catch {
      notify(t("Invalid username or password"), "danger");
    }
  }

  async function logout() {
    try {
      await callApi("/api/auth/logout", { method: "POST" });
    } finally {
      setSession(null);
      setCurrentUser(null);
      navigate("/overview", { replace: true });
      setSelectedReportId(null);
      setReportDetail(null);
      setLlmDiagnostics(null);
      setLlmSetupGuide(null);
      clearClusterDetail();
    }
  }

  async function createCluster(form: ClusterCreateForm) {
    const cluster = await callApi<ClusterView>("/api/clusters", {
      method: "POST",
      body: {
        name: form.name,
        environment: form.environment,
        description: form.description,
      },
    });
    notify(t("Cluster created."));
    setSelectedCluster(cluster);
    navigate(clusterPath(cluster.cluster_id));
    await Promise.all([
      loadConsoleData(true),
      loadClusterDetail(cluster),
      generateInstallCommand(cluster.cluster_id, form.backend_url),
    ]);
  }

  async function deleteCluster(cluster: ClusterView, confirmName: string) {
    const query = new URLSearchParams({ confirm_name: confirmName });
    await callApi(`/api/clusters/${encodeURIComponent(cluster.cluster_id)}?${query}`, { method: "DELETE" });
    setDeleteDialog(null);
    setSelectedCluster(null);
    setClusterDetail(null);
    setInstallCommand(null);
    if (route.clusterId === cluster.cluster_id) navigate("/clusters", { replace: true });
    notify(t("Cluster deleted."));
    await loadConsoleData(true);
  }

  async function rotateAgentToken(cluster: ClusterView) {
    const result = await callApi<AgentTokenRotateResponse>(
      `/api/clusters/${encodeURIComponent(cluster.cluster_id)}/agent-token/rotate`,
      { method: "POST" },
    );
    notify(t("Agent token rotated."));
    setInstallCommand({
      cluster_id: cluster.cluster_id,
      namespace: "cluster-infra-rca",
      commands: [`New agent token: ${result.agent_token || ""}`],
      notes: result.note ? [result.note] : [],
    });
  }

  async function startCollection(cluster: ClusterView, nodeName = "") {
    await callApi(`/api/clusters/${encodeURIComponent(cluster.cluster_id)}/collection-runs`, {
      method: "POST",
      body: {
        confirmed: true,
        alert_name: "BackendManualCollection",
        node_names: nodeName ? [nodeName] : [],
        requested_collectors: [],
        reason: "Manual evidence collection requested from Web Console.",
        context: { source: "web_console" },
      },
    });
    notify(t("Evidence collection requested."));
    await loadClusterDetail(cluster);
  }

  async function updateClusterThresholds(cluster: ClusterView, thresholds: Record<string, number>, reason: string) {
    const settings = await callApi<ClusterThresholdSettings>(
      `/api/clusters/${encodeURIComponent(cluster.cluster_id)}/thresholds`,
      { method: "PUT", body: { thresholds, reason } },
    );
    setClusterDetail((current) => current ? { ...current, thresholds: settings } : current);
    notify(t("Threshold overrides saved."));
  }

  async function clearClusterThresholds(cluster: ClusterView) {
    const settings = await callApi<ClusterThresholdSettings>(
      `/api/clusters/${encodeURIComponent(cluster.cluster_id)}/thresholds`,
      { method: "DELETE" },
    );
    setClusterDetail((current) => current ? { ...current, thresholds: settings } : current);
    notify(t("Threshold overrides cleared."));
  }

  async function executeRecommendedAction(report: RcaReport, actionIndex: number, note: string) {
    const response = await callApi<{ message?: string }>(
      `/api/rca/reports/${encodeURIComponent(report.report_id)}/actions/${actionIndex}/execute`,
      { method: "POST", body: { confirmed: true, note } },
    );
    setActionDialog(null);
    notify(response.message || t("Action request updated."));
    await loadReportDetail(report.report_id);
    await loadConsoleData(true);
  }

  async function decideActionRequest(actionRequest: ActionRequestView, decision: "approve" | "reject", note = "") {
    await callApi(`/api/rca/action-requests/${encodeURIComponent(actionRequest.action_request_id)}/${decision}`, {
      method: "POST",
      body: { confirmed: true, note },
    });
    notify(t(decision === "approve" ? "Action request approved." : "Action request rejected."));
    if (actionRequest.report_id) {
      await loadReportDetail(actionRequest.report_id);
    }
    await loadConsoleData(true);
  }

  async function completeManualAction(actionRequest: ActionRequestView, note: string) {
    await callApi(`/api/rca/action-requests/${encodeURIComponent(actionRequest.action_request_id)}/complete-manual`, {
      method: "POST",
      body: { confirmed: true, note },
    });
    notify(t("Manual handling completed."));
    if (actionRequest.report_id) {
      await loadReportDetail(actionRequest.report_id);
    }
    await loadConsoleData(true);
  }

  async function changeIncidentStatus(incident: IncidentView, nextStatus: "resolve" | "reopen") {
    await callApi(`/api/rca/incidents/${encodeURIComponent(incident.incident_id)}/${nextStatus}`, {
      method: "POST",
      body: { confirmed: true, note: "Updated from Web Console." },
    });
    notify(t(nextStatus === "resolve" ? "Incident resolved." : "Incident reopened."));
    await loadConsoleData(true);
  }

  async function retryAnalysisTask(task: AnalysisTaskView) {
    await callApi(`/api/rca/analysis-tasks/${encodeURIComponent(task.task_id)}/retry`, {
      method: "POST",
      body: { confirmed: true, note: "Retry requested from Web Console." },
    });
    notify(t("Analysis task requeued."));
    await loadConsoleData(true);
  }

  async function runDemoScenario(scenario: DemoScenarioView, clusterId: string, nodeName: string) {
    await callApi(`/api/demo/scenarios/${encodeURIComponent(scenario.key)}/run`, {
      method: "POST",
      body: { confirmed: true, cluster_id: clusterId || null, node_name: nodeName || null },
    });
    notify(t("Demo scenario started."));
    await loadConsoleData(true);
  }

  async function changePassword(form: PasswordChangeForm) {
    await callApi("/api/auth/change-password", {
      method: "POST",
      body: { current_password: form.current_password, new_password: form.new_password },
    });
    notify(t("Password changed."));
  }

  async function changeLoginId(form: LoginIdChangeForm) {
    const updatedUser = await callApi<UserAccount>("/api/auth/change-login-id", {
      method: "POST",
      body: { current_password: form.current_password, new_username: form.new_username },
    });
    setCurrentUser(updatedUser);
    setSession((value) => value ? { ...value, user: updatedUser } : { user: updatedUser });
    notify(t("Login ID changed."));
  }

  async function exportReports(clusterId = "") {
    const suffix = clusterId ? `?cluster_id=${encodeURIComponent(clusterId)}` : "";
    await downloadApi(`/api/rca/reports/export${suffix}`, clusterId ? `rca-reports-${clusterId}.json` : "rca-reports.json");
    notify(t("Export downloaded."));
  }

  async function exportReport(reportId: string) {
    await downloadApi(`/api/rca/reports/${encodeURIComponent(reportId)}/export`, `rca-report-${reportId}.json`);
    notify(t("Report exported."));
  }

  async function exportEvidenceBundle(reportId: string) {
    await downloadApi(`/api/rca/reports/${encodeURIComponent(reportId)}/bundle`, `rca-evidence-bundle-${reportId}.zip`);
    notify(t("Evidence bundle downloaded."));
  }

  async function exportAudit(format = "json", filters: Record<string, unknown> = {}) {
    const query = buildAuditQuery({ ...filters, format, limit: 5000 });
    await downloadApi(`/api/audit/events/export?${query}`, `audit-events.${format}`);
    notify(t("Audit export downloaded."));
  }

  async function testNotificationDelivery(): Promise<NotificationTestResponse> {
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
    if (["admin", "auditor"].includes(currentUser?.role || "")) {
      const history = await callApi<AuditEventView[]>("/api/notifications/history?limit=50");
      setNotificationHistory(sortByTime(Array.isArray(history) ? history : [], "created_at"));
    }
    return response;
  }

  async function testLlmConnection(): Promise<LlmTestResponse> {
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
    const diagnostics = await callApi<LlmDiagnosticResponse>("/api/llm/diagnostics");
    setLlmDiagnostics(diagnostics);
    if (["admin", "auditor"].includes(currentUser?.role || "")) {
      const audit = await callApi<AuditEventView[]>("/api/audit/events?limit=200");
      setAuditEvents(sortByTime(Array.isArray(audit) ? audit : [], "created_at"));
    }
    return response;
  }

  async function reloadCatalogOverrideDrafts() {
    if (!["admin", "operator", "approver", "auditor"].includes(currentUser?.role || "")) {
      setCatalogOverrideDrafts([]);
      return;
    }
    const drafts = await callApi<CatalogOverrideDraft[]>("/api/v1/catalog/overrides/drafts?limit=50");
    setCatalogOverrideDrafts(sortByTime(Array.isArray(drafts) ? drafts : [], "created_at"));
  }

  async function previewCatalogOverride(overrideJson: string, reason: string): Promise<CatalogOverridePreviewResponse> {
    const response = await callApi<CatalogOverridePreviewResponse>("/api/v1/catalog/preview", {
      method: "POST",
      body: { override_json: overrideJson, reason },
    });
    notify(
      response.valid ? t("Catalog override preview completed.") : t("Catalog override preview rejected."),
      response.valid ? "success" : "warning",
    );
    if (["admin", "auditor"].includes(currentUser?.role || "")) {
      const audit = await callApi<AuditEventView[]>("/api/audit/events?limit=200");
      setAuditEvents(sortByTime(Array.isArray(audit) ? audit : [], "created_at"));
    }
    return response;
  }

  async function createCatalogOverrideDraft(overrideJson: string, reason: string): Promise<CatalogOverrideDraft> {
    const draft = await callApi<CatalogOverrideDraft>("/api/v1/catalog/overrides/drafts", {
      method: "POST",
      body: { override_json: overrideJson, reason },
    });
    notify(t("Catalog override draft saved."));
    await reloadCatalogOverrideDrafts();
    if (["admin", "auditor"].includes(currentUser?.role || "")) {
      const audit = await callApi<AuditEventView[]>("/api/audit/events?limit=200");
      setAuditEvents(sortByTime(Array.isArray(audit) ? audit : [], "created_at"));
    }
    return draft;
  }

  async function decideCatalogOverrideDraft(
    draft: CatalogOverrideDraft,
    decision: "approve" | "reject" | "discard",
    note: string,
  ): Promise<CatalogOverrideDraft> {
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
    await reloadCatalogOverrideDrafts();
    if (["admin", "auditor"].includes(currentUser?.role || "")) {
      const audit = await callApi<AuditEventView[]>("/api/audit/events?limit=200");
      setAuditEvents(sortByTime(Array.isArray(audit) ? audit : [], "created_at"));
    }
    return updated;
  }

  async function loadCatalogOverrideHandoff(draft: CatalogOverrideDraft): Promise<CatalogOverrideHandoff> {
    return callApi<CatalogOverrideHandoff>(
      `/api/v1/catalog/overrides/drafts/${encodeURIComponent(draft.draft_id)}/handoff`,
    );
  }

  async function createCatalogGitOpsChange(draft: CatalogOverrideDraft): Promise<GitOpsChange> {
    const change = await callApi<GitOpsChange>(
      `/api/v1/catalog/overrides/drafts/${encodeURIComponent(draft.draft_id)}/gitops-changes`,
      { method: "POST", body: { confirmed: true } },
    );
    notify(t(change.pull_request_state === "failed" ? "GitOps PR creation failed." : "GitOps pull request created."), change.pull_request_state === "failed" ? "danger" : "success");
    return change;
  }

  async function loadCatalogGitOpsChanges(draft: CatalogOverrideDraft): Promise<GitOpsChange[]> {
    const query = new URLSearchParams({
      sourceType: "catalog_override_draft",
      sourceId: draft.draft_id,
      limit: "10",
    });
    const changes = await callApi<GitOpsChange[]>(`/api/v1/gitops/changes?${query.toString()}`);
    return Array.isArray(changes) ? changes : [];
  }

  async function updateGitOpsOutcome(
    change: GitOpsChange,
    state: GitOpsDeploymentState,
    verificationResult: string,
    rollbackReference: string,
  ): Promise<GitOpsChange> {
    const updated = await callApi<GitOpsChange>(`/api/v1/gitops/changes/${encodeURIComponent(change.change_id)}/outcome`, {
      method: "POST",
      body: {
        confirmed: true,
        deployment_state: state,
        verification_result: verificationResult,
        rollback_reference: rollbackReference,
      },
    });
    notify(t("GitOps deployment state recorded."));
    return updated;
  }

  if (bootLoading) {
    return <BootScreen t={t} />;
  }

  if (!currentUser) {
    return <LoginPage onLogin={login} locale={locale} setLocale={setLocale} t={t} toast={toast} />;
  }

  const visibleNav = NAV_ITEMS.filter((item) => !item.roles || item.roles.includes(currentUser.role));
  const webhookEndpoint = `${window.location.origin.replace(/\/$/, "")}/api/webhooks/alertmanager`;
  const routeResourceMissing =
    activeView === "clusters" && route.clusterId && loadStates.clusters.loadedAt && !loadStates.clusters.error
      ? !clusters.some((item) => item.cluster_id === route.clusterId)
      : activeView === "reports" && route.reportId && loadStates.reports.loadedAt && !loadStates.reports.error
        ? !reports.some((item) => item.report_id === route.reportId)
        : activeView === "incidents" && route.incidentId && loadStates.incidents.loadedAt && !loadStates.incidents.error
          ? !incidents.some((item) => item.incident_id === route.incidentId)
          : false;
  const routeResourceId = route.clusterId || route.reportId || route.incidentId || "";

  return (
    <div className="console-shell">
      <Sidebar items={visibleNav} activeView={activeView} onNavigate={navigateToView} t={t} />
      <div className="console-main">
        <Topbar
          user={currentUser}
          locale={locale}
          setLocale={setLocale}
          onRefresh={() => loadConsoleData(false)}
          onLogout={logout}
          loading={loadingData}
          degraded={Object.values(loadStates).some((state) => Boolean(state.error))}
          lastUpdatedAt={lastUpdatedAt}
          t={t}
        />
        <main className="console-content" data-testid={`view-${activeView}`}>
          <DataStatusBanner
            states={loadStates}
            lastCompleteRefreshAt={lastCompleteRefreshAt}
            onRetry={() => loadConsoleData(false)}
            t={t}
          />
          {routeResourceMissing && (
            <RouteStatusNotice
              resourceId={routeResourceId}
              onReturn={() => navigate(pathForView(activeView), { replace: true })}
              t={t}
            />
          )}
          {activeView === "overview" && (
            <OverviewView
              clusters={clusters}
              reports={reports}
              incidents={incidents}
              analysisTasks={analysisTasks}
              actionRequests={actionRequests}
              agentHealth={agentHealth}
              onNavigate={navigateToView}
              onOpenReport={openReport}
              onOpenCluster={openClusterDetail}
              webhookEndpoint={webhookEndpoint}
              t={t}
            />
          )}
          {activeView === "clusters" && (
            <ClustersView
              clusters={clusters}
              selectedCluster={selectedCluster}
              clusterDetail={clusterDetail}
              agentHealth={agentHealth}
              installCommand={installCommand}
              currentUser={currentUser}
              onCreate={createCluster}
              onSelect={openClusterDetail}
              onGenerateInstall={generateInstallCommand}
              onStartCollection={startCollection}
              onUpdateThresholds={updateClusterThresholds}
              onClearThresholds={clearClusterThresholds}
              onDelete={(cluster: ClusterView) => setDeleteDialog({ cluster })}
              onRotateToken={rotateAgentToken}
              onCopy={(text: string) => copyText(text, notify)}
              t={t}
            />
          )}
          {activeView === "reports" && (
            <ReportsView
              reports={reports}
              selectedReportId={selectedReportId}
              setSelectedReportId={openReport}
              detail={reportDetail}
              currentUser={currentUser}
              onPrepareAction={(report, action, index) => setActionDialog({ report, action, index })}
              onDecideAction={decideActionRequest}
              onCompleteManual={completeManualAction}
              onExportReport={exportReport}
              onExportBundle={exportEvidenceBundle}
              onExportAll={() => exportReports()}
              platformInfo={platformInfo}
              onCopy={(text: string) => copyText(text, notify)}
              t={t}
            />
          )}
          {activeView === "incidents" && (
            <IncidentsView
              incidents={incidents}
              selectedIncidentId={route.incidentId}
              onSelectIncident={openIncident}
              onOpenReport={openReport}
              onChangeStatus={changeIncidentStatus}
              currentUser={currentUser}
              t={t}
            />
          )}
          {activeView === "pipeline" && (
            <PipelineView
              tasks={analysisTasks}
              actionRequests={actionRequests}
              demoScenarios={demoScenarios}
              clusters={clusters}
              agentHealth={agentHealth}
              onRetry={retryAnalysisTask}
              onRunDemo={runDemoScenario}
              t={t}
            />
          )}
          {activeView === "audit" && (
            <AuditView
              events={auditEvents}
              onSearch={async (filters: Record<string, unknown>) => {
                const query = buildAuditQuery(filters);
                const next = await callApi<AuditEventView[]>(`/api/audit/events?${query}`);
                setAuditEvents(sortByTime(Array.isArray(next) ? next : [], "created_at"));
              }}
              onExport={exportAudit}
              t={t}
            />
          )}
          {activeView === "webhooks" && (
            <WebhooksView endpoint={webhookEndpoint} onCopy={(text: string) => copyText(text, notify)} t={t} />
          )}
          {activeView === "settings" && (
            <SettingsView
              locale={locale}
              setLocale={setLocale}
              platformInfo={platformInfo}
              catalogDetail={catalogDetail}
              catalogOverrideDrafts={catalogOverrideDrafts}
              llmDiagnostics={llmDiagnostics}
              llmSetupGuide={llmSetupGuide}
              notificationHistory={notificationHistory}
              currentUser={currentUser}
              onChangeLoginId={changeLoginId}
              onChangePassword={changePassword}
              onTestNotification={testNotificationDelivery}
              onTestLlm={testLlmConnection}
              onPreviewCatalogOverride={previewCatalogOverride}
              onCreateCatalogOverrideDraft={createCatalogOverrideDraft}
              onDecideCatalogOverrideDraft={decideCatalogOverrideDraft}
              onLoadCatalogOverrideHandoff={loadCatalogOverrideHandoff}
              onCreateCatalogGitOpsChange={createCatalogGitOpsChange}
              onLoadCatalogGitOpsChanges={loadCatalogGitOpsChanges}
              onUpdateGitOpsOutcome={updateGitOpsOutcome}
              t={t}
            />
          )}
        </main>
      </div>
      {toast && <Toast tone={toast.tone} message={toast.message} />}
      {actionDialog && (
        <ActionDialog
          state={actionDialog}
          onClose={() => setActionDialog(null)}
          onConfirm={executeRecommendedAction}
          t={t}
        />
      )}
      {deleteDialog && (
        <DeleteClusterDialog
          state={deleteDialog}
          onClose={() => setDeleteDialog(null)}
          onConfirm={deleteCluster}
          t={t}
        />
      )}
    </div>
  );
}

const rootElement = document.getElementById("rca-console-root");
if (!rootElement) {
  throw new Error("Missing #rca-console-root element.");
}

createRoot(rootElement).render(
  <BrowserRouter>
    <ConsoleApp />
  </BrowserRouter>,
);
