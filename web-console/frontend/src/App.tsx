import { useCallback, useEffect, useState } from "react";
import { createRoot } from "react-dom/client";
import "bootstrap/dist/css/bootstrap.min.css";
import "bootstrap-icons/font/bootstrap-icons.css";
import "./styles.css";

import { requestCurrentUser } from "./api/client";
import { ActionDialog, BootScreen, DeleteClusterDialog, LoginPage, Sidebar, Toast, Topbar } from "./components/common";
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
import type {
  ActionDialogState,
  ActionRequestView,
  AgentTokenRotateResponse,
  AnalysisTaskView,
  AuditEventView,
  AuthSession,
  ClusterCreateForm,
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
  const [activeView, setActiveView] = useState("overview");
  const [bootLoading, setBootLoading] = useState(true);
  const [actionDialog, setActionDialog] = useState<ActionDialogState | null>(null);
  const [deleteDialog, setDeleteDialog] = useState<DeleteClusterDialogState | null>(null);

  const { callApi, downloadApi } = useAuthenticatedApi(session);
  const {
    loadingData,
    clusters,
    reports,
    incidents,
    analysisTasks,
    actionRequests,
    agentHealth,
    auditEvents,
    notificationHistory,
    demoScenarios,
    platformInfo,
    llmDiagnostics,
    llmSetupGuide,
    setAuditEvents,
    setNotificationHistory,
    setLlmDiagnostics,
    setLlmSetupGuide,
    loadConsoleData,
  } = useConsoleData(callApi, currentUser, notify, t);
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
    if (currentUser) {
      void loadConsoleData(true);
    }
  }, [currentUser, loadConsoleData]);

  useEffect(() => {
    if (!selectedReportId && reports.length) {
      setSelectedReportId(reports[0].report_id);
    }
  }, [reports, selectedReportId, setSelectedReportId]);

  const openClusterDetail = useCallback(async (cluster: ClusterView | null) => {
    if (!cluster) return;
    setActiveView("clusters");
    await loadClusterDetail(cluster);
  }, [loadClusterDetail]);

  async function login(form: LoginForm) {
    try {
      const nextSession = await callApi<AuthSession>("/api/auth/login", { method: "POST", body: form });
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
      setActiveView("overview");
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
    await loadConsoleData(true);
    await generateInstallCommand(cluster.cluster_id, form.backend_url);
    setSelectedCluster(cluster);
    setActiveView("clusters");
  }

  async function deleteCluster(cluster: ClusterView, confirmName: string) {
    const query = new URLSearchParams({ confirm_name: confirmName });
    await callApi(`/api/clusters/${encodeURIComponent(cluster.cluster_id)}?${query}`, { method: "DELETE" });
    setDeleteDialog(null);
    setSelectedCluster(null);
    setClusterDetail(null);
    setInstallCommand(null);
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

  if (bootLoading) {
    return <BootScreen t={t} />;
  }

  if (!currentUser) {
    return <LoginPage onLogin={login} locale={locale} setLocale={setLocale} t={t} toast={toast} />;
  }

  const visibleNav = NAV_ITEMS.filter((item) => !item.roles || item.roles.includes(currentUser.role));
  const webhookEndpoint = `${window.location.origin.replace(/\/$/, "")}/api/webhooks/alertmanager`;

  return (
    <div className="console-shell">
      <Sidebar items={visibleNav} activeView={activeView} setActiveView={setActiveView} t={t} />
      <div className="console-main">
        <Topbar
          user={currentUser}
          locale={locale}
          setLocale={setLocale}
          onRefresh={() => loadConsoleData(false)}
          onLogout={logout}
          loading={loadingData}
          t={t}
        />
        <main className="console-content" data-testid={`view-${activeView}`}>
          {activeView === "overview" && (
            <OverviewView
              clusters={clusters}
              reports={reports}
              incidents={incidents}
              analysisTasks={analysisTasks}
              actionRequests={actionRequests}
              agentHealth={agentHealth}
              onNavigate={setActiveView}
              onOpenReport={setSelectedReportId}
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
              setSelectedReportId={setSelectedReportId}
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
              onOpenReport={(id: string) => {
                setSelectedReportId(id);
                setActiveView("reports");
              }}
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
              llmDiagnostics={llmDiagnostics}
              llmSetupGuide={llmSetupGuide}
              notificationHistory={notificationHistory}
              currentUser={currentUser}
              onChangeLoginId={changeLoginId}
              onChangePassword={changePassword}
              onTestNotification={testNotificationDelivery}
              onTestLlm={testLlmConnection}
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

createRoot(rootElement).render(<ConsoleApp />);
