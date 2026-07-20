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
import { useActionWorkflow } from "./hooks/useActionWorkflow";
import { useAuditSearch } from "./hooks/useAuditSearch";
import { useClusterDetail } from "./hooks/useClusterDetail";
import { useClusterOperations } from "./hooks/useClusterOperations";
import { useConsoleData } from "./hooks/useConsoleData";
import { useConsoleLocale } from "./hooks/useConsoleLocale";
import { useOperationalActions } from "./hooks/useOperationalActions";
import { useReportDetail } from "./hooks/useReportDetail";
import { useSettingsOperations } from "./hooks/useSettingsOperations";
import { useToast } from "./hooks/useToast";
import { AuditView } from "./pages/Audit";
import { ClustersView } from "./pages/Clusters";
import { IncidentsView } from "./pages/Incidents";
import { OverviewView } from "./pages/Overview";
import { PipelineView } from "./pages/Pipeline";
import { ReportsView } from "./pages/Reports";
import { SettingsView } from "./pages/Settings";
import { WebhooksView } from "./pages/Webhooks";
import { copyText } from "./lib/consoleUtils";
import { clusterPath, incidentPath, parseConsoleRoute, pathForView, reportPath } from "./routing";
import type {
  AuthSession,
  ClusterView,
  LoginForm,
  UserAccount,
} from "./types";

function ConsoleApp() {
  const { locale, setLocale, t } = useConsoleLocale();
  const { toast, notify } = useToast();
  const [session, setSession] = useState<AuthSession | null>(null);
  const [currentUser, setCurrentUser] = useState<UserAccount | null>(null);
  const [bootLoading, setBootLoading] = useState(true);
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

  const navigateToCluster = useCallback((clusterId: string) => {
    navigate(clusterPath(clusterId));
  }, [navigate]);

  const navigateToClusterList = useCallback(() => {
    navigate("/clusters", { replace: true });
  }, [navigate]);

  const {
    deleteDialog,
    setDeleteDialog,
    createCluster,
    deleteCluster,
    rotateAgentToken,
    startCollection,
    updateClusterThresholds,
    clearClusterThresholds,
  } = useClusterOperations({
    callApi,
    notify,
    t,
    routeClusterId: route.clusterId,
    navigateToCluster,
    navigateToClusterList,
    loadConsoleData,
    loadClusterDetail,
    generateInstallCommand,
    setSelectedCluster,
    setClusterDetail,
    setInstallCommand,
  });

  const {
    actionDialog,
    setActionDialog,
    executeRecommendedAction,
    decideActionRequest,
    completeManualAction,
  } = useActionWorkflow({
    callApi,
    notify,
    t,
    loadReportDetail,
    loadConsoleData,
  });

  const {
    changeIncidentStatus,
    retryAnalysisTask,
    runDemoScenario,
    exportReports,
    exportReport,
    exportEvidenceBundle,
  } = useOperationalActions({
    callApi,
    downloadApi,
    notify,
    t,
    loadConsoleData,
  });

  const { searchAudit, exportAudit } = useAuditSearch({
    callApi,
    downloadApi,
    notify,
    t,
    setAuditEvents,
  });

  const {
    changePassword,
    changeLoginId,
    testNotificationDelivery,
    testLlmConnection,
    previewCatalogOverride,
    createCatalogOverrideDraft,
    decideCatalogOverrideDraft,
    loadCatalogOverrideHandoff,
    createCatalogGitOpsChange,
    loadCatalogGitOpsChanges,
    updateGitOpsOutcome,
  } = useSettingsOperations({
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
  });

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
              callApi={callApi}
              clusters={clusters}
              refreshToken={lastUpdatedAt}
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
              callApi={callApi}
              clusters={clusters}
              refreshToken={lastUpdatedAt}
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
              callApi={callApi}
              refreshToken={lastUpdatedAt}
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
              onSearch={searchAudit}
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
