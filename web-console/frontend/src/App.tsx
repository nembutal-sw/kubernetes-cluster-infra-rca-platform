import { useCallback, useEffect, useState } from "react";
import { createRoot } from "react-dom/client";
import { BrowserRouter } from "react-router";
import "bootstrap/dist/css/bootstrap.min.css";
import "bootstrap-icons/font/bootstrap-icons.css";
import "./styles.css";

import { requestCurrentUser } from "./api/client";
import { ActionDialog, BootScreen, DeleteClusterDialog, LoginPage, Sidebar, Toast, Topbar } from "./components/common";
import { ConsoleViewHost } from "./components/ConsoleViewHost";
import { NAV_ITEMS } from "./constants";
import { useActionWorkflow } from "./hooks/useActionWorkflow";
import { useAuditSearch } from "./hooks/useAuditSearch";
import { useAuthenticatedApi } from "./hooks/useAuthenticatedApi";
import { useClusterDetail } from "./hooks/useClusterDetail";
import { useClusterOperations } from "./hooks/useClusterOperations";
import { useConsoleData } from "./hooks/useConsoleData";
import { useConsoleLocale } from "./hooks/useConsoleLocale";
import { useConsoleNavigation } from "./hooks/useConsoleNavigation";
import { useOperationalActions } from "./hooks/useOperationalActions";
import { useReportDetail } from "./hooks/useReportDetail";
import { useRouteResourceSync } from "./hooks/useRouteResourceSync";
import { useSettingsOperations } from "./hooks/useSettingsOperations";
import { useToast } from "./hooks/useToast";
import type { AuthSession, LoginForm, UserAccount } from "./types";

function ConsoleApp() {
  const { locale, setLocale, t } = useConsoleLocale();
  const { toast, notify } = useToast();
  const [session, setSession] = useState<AuthSession | null>(null);
  const [currentUser, setCurrentUser] = useState<UserAccount | null>(null);
  const [bootLoading, setBootLoading] = useState(true);
  const navigation = useConsoleNavigation(currentUser);

  const handleUnauthorized = useCallback(() => {
    setSession(null);
    setCurrentUser(null);
    notify(t("Your session expired. Sign in again."), "warning");
  }, [notify, t]);

  const { callApi, downloadApi } = useAuthenticatedApi(session, handleUnauthorized);
  const data = useConsoleData(callApi, currentUser, navigation.activeView);
  const clusterDetail = useClusterDetail(callApi);
  const reportDetail = useReportDetail(callApi, currentUser, notify, t);

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
      void data.loadConsoleData(true);
    }
  }, [currentUser, data.loadConsoleData]);

  useEffect(() => {
    if (!currentUser) return undefined;
    const timer = window.setInterval(() => {
      if (document.visibilityState === "visible") {
        void data.loadConsoleData(true);
      }
    }, 30000);
    return () => window.clearInterval(timer);
  }, [currentUser, data.loadConsoleData]);

  useRouteResourceSync({
    currentUser: Boolean(currentUser),
    activeView: navigation.activeView,
    route: navigation.route,
    clusters: data.clusters,
    selectedCluster: clusterDetail.selectedCluster,
    clearClusterDetail: clusterDetail.clearClusterDetail,
    loadClusterDetail: clusterDetail.loadClusterDetail,
    selectedReportId: reportDetail.selectedReportId,
    setSelectedReportId: reportDetail.setSelectedReportId,
    setReportDetail: reportDetail.setReportDetail,
  });

  const clusterOperations = useClusterOperations({
    callApi,
    notify,
    t,
    routeClusterId: navigation.route.clusterId,
    navigateToCluster: navigation.navigateToCluster,
    navigateToClusterList: navigation.navigateToClusterList,
    loadConsoleData: data.loadConsoleData,
    loadClusterDetail: clusterDetail.loadClusterDetail,
    generateInstallCommand: clusterDetail.generateInstallCommand,
    setSelectedCluster: clusterDetail.setSelectedCluster,
    setClusterDetail: clusterDetail.setClusterDetail,
    setInstallCommand: clusterDetail.setInstallCommand,
  });

  const actionWorkflow = useActionWorkflow({
    callApi,
    notify,
    t,
    loadReportDetail: reportDetail.loadReportDetail,
    loadConsoleData: data.loadConsoleData,
  });

  const operationalActions = useOperationalActions({
    callApi,
    downloadApi,
    notify,
    t,
    loadConsoleData: data.loadConsoleData,
  });

  const auditSearch = useAuditSearch({
    callApi,
    downloadApi,
    notify,
    t,
    setAuditEvents: data.setAuditEvents,
  });

  const settingsOperations = useSettingsOperations({
    callApi,
    currentUser,
    setCurrentUser,
    setSession,
    setNotificationHistory: data.setNotificationHistory,
    setAuditEvents: data.setAuditEvents,
    setCatalogOverrideDrafts: data.setCatalogOverrideDrafts,
    setLlmDiagnostics: data.setLlmDiagnostics,
    notify,
    t,
  });

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
      navigation.resetToOverview();
      reportDetail.setSelectedReportId(null);
      reportDetail.setReportDetail(null);
      data.setLlmDiagnostics(null);
      data.setLlmSetupGuide(null);
      clusterDetail.clearClusterDetail();
    }
  }

  if (bootLoading) {
    return <BootScreen t={t} />;
  }

  if (!currentUser) {
    return <LoginPage onLogin={login} locale={locale} setLocale={setLocale} t={t} toast={toast} />;
  }

  const visibleNav = NAV_ITEMS.filter((item) => !item.roles || item.roles.includes(currentUser.role));

  return (
    <div className="console-shell">
      <Sidebar items={visibleNav} activeView={navigation.activeView} onNavigate={navigation.navigateToView} t={t} />
      <div className="console-main">
        <Topbar
          user={currentUser}
          locale={locale}
          setLocale={setLocale}
          onRefresh={() => data.loadConsoleData(false)}
          onLogout={logout}
          loading={data.loadingData}
          degraded={Object.values(data.activeLoadStates).some((state) => Boolean(state.error))}
          lastUpdatedAt={data.lastUpdatedAt}
          t={t}
        />
        <main className="console-content" data-testid={`view-${navigation.activeView}`}>
          <ConsoleViewHost
            callApi={callApi}
            currentUser={currentUser}
            locale={locale}
            setLocale={setLocale}
            t={t}
            notify={notify}
            data={data}
            clusterDetail={clusterDetail}
            clusterOperations={clusterOperations}
            reportDetail={reportDetail}
            actionWorkflow={actionWorkflow}
            operationalActions={operationalActions}
            auditSearch={auditSearch}
            settingsOperations={settingsOperations}
            navigation={navigation}
          />
        </main>
      </div>
      {toast && <Toast tone={toast.tone} message={toast.message} />}
      {actionWorkflow.actionDialog && (
        <ActionDialog
          state={actionWorkflow.actionDialog}
          onClose={() => actionWorkflow.setActionDialog(null)}
          onConfirm={actionWorkflow.executeRecommendedAction}
          t={t}
        />
      )}
      {clusterOperations.deleteDialog && (
        <DeleteClusterDialog
          state={clusterOperations.deleteDialog}
          onClose={() => clusterOperations.setDeleteDialog(null)}
          onConfirm={clusterOperations.deleteCluster}
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
