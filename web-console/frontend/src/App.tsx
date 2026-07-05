// @ts-nocheck
import React, { useCallback, useEffect, useState } from "react";
import { createRoot } from "react-dom/client";
import "bootstrap/dist/css/bootstrap.min.css";
import "bootstrap-icons/font/bootstrap-icons.css";
import "./styles.css";
import { requestCurrentUser } from "./api/client";
import { NAV_ITEMS, KO, STORAGE_KEYS } from "./constants";
import { ActionDialog, BootScreen, DeleteClusterDialog, LoginPage, Sidebar, Toast, Topbar } from "./components/common";
import { AuditView } from "./pages/Audit";
import { ClustersView } from "./pages/Clusters";
import { IncidentsView } from "./pages/Incidents";
import { OverviewView } from "./pages/Overview";
import { PipelineView } from "./pages/Pipeline";
import { ReportsView } from "./pages/Reports";
import { SettingsView } from "./pages/Settings";
import { WebhooksView } from "./pages/Webhooks";
import { useAuthenticatedApi } from "./hooks/useAuthenticatedApi";
import { arrayResult, buildAuditQuery, copyText, runConsoleLayoutAudit, sortByTime } from "./lib/consoleUtils";

function ConsoleApp() {
  const [locale, setLocale] = useState(() => localStorage.getItem(STORAGE_KEYS.locale) || "en");
  const [session, setSession] = useState(null);
  const [currentUser, setCurrentUser] = useState(null);
  const [activeView, setActiveView] = useState("overview");
  const [loading, setLoading] = useState({ boot: true, data: false });
  const [toast, setToast] = useState(null);
  const [clusters, setClusters] = useState([]);
  const [reports, setReports] = useState([]);
  const [incidents, setIncidents] = useState([]);
  const [analysisTasks, setAnalysisTasks] = useState([]);
  const [actionRequests, setActionRequests] = useState([]);
  const [agentHealth, setAgentHealth] = useState([]);
  const [auditEvents, setAuditEvents] = useState([]);
  const [demoScenarios, setDemoScenarios] = useState([]);
  const [platformInfo, setPlatformInfo] = useState(null);
  const [selectedCluster, setSelectedCluster] = useState(null);
  const [clusterDetail, setClusterDetail] = useState(null);
  const [selectedReportId, setSelectedReportId] = useState(null);
  const [reportDetail, setReportDetail] = useState(null);
  const [installCommand, setInstallCommand] = useState(null);
  const [actionDialog, setActionDialog] = useState(null);
  const [deleteDialog, setDeleteDialog] = useState(null);

  const t = useCallback((key) => (locale === "ko" ? KO[key] || key : key), [locale]);

  useEffect(() => {
    localStorage.setItem(STORAGE_KEYS.locale, locale);
    document.documentElement.lang = locale === "ko" ? "ko" : "en";
  }, [locale]);

  const notify = useCallback((message, tone = "success") => {
    setToast({ message, tone });
    window.setTimeout(() => setToast(null), 3200);
  }, []);

  const { callApi, downloadApi } = useAuthenticatedApi(session);

  const loadConsoleData = useCallback(async (silent = false) => {
    if (!silent) setLoading((value) => ({ ...value, data: true }));
    try {
      const requests = [
        callApi("/api/clusters"),
        callApi("/api/rca/reports"),
        callApi("/api/rca/incidents"),
        callApi("/api/rca/analysis-tasks?limit=300"),
        callApi("/api/rca/action-requests"),
        callApi("/api/demo/scenarios"),
        callApi("/api/v1/platform/info"),
      ];
      if (["admin", "auditor"].includes(currentUser?.role)) {
        requests.push(callApi("/api/audit/events?limit=200"));
      }
      const results = await Promise.allSettled(requests);
      const clusterItems = arrayResult(results[0]);
      setClusters(clusterItems);
      setReports(sortByTime(arrayResult(results[1]), "created_at"));
      setIncidents(sortByTime(arrayResult(results[2]), "last_seen_at"));
      setAnalysisTasks(sortByTime(arrayResult(results[3]), "created_at"));
      setActionRequests(sortByTime(arrayResult(results[4]), "created_at"));
      setDemoScenarios(arrayResult(results[5]));
      setPlatformInfo(results[6].status === "fulfilled" ? results[6].value : null);
      if (["admin", "auditor"].includes(currentUser?.role)) {
        setAuditEvents(sortByTime(arrayResult(results[7]), "created_at"));
      } else {
        setAuditEvents([]);
      }
      if (clusterItems.length) {
        const healthResults = await Promise.allSettled(
          clusterItems.map((cluster) => callApi(`/api/clusters/${encodeURIComponent(cluster.cluster_id)}/agent-health`)),
        );
        setAgentHealth(healthResults.flatMap((result) => arrayResult(result)));
      } else {
        setAgentHealth([]);
      }
    } catch (error) {
      notify(error.message || "Failed to load console data.", "danger");
    } finally {
      setLoading((value) => ({ ...value, data: false }));
    }
  }, [callApi, currentUser?.role, notify]);

  useEffect(() => {
    async function boot() {
      try {
        const user = await requestCurrentUser();
        setCurrentUser(user);
        setSession({ user });
      } catch {
        setCurrentUser(null);
      } finally {
        setLoading((value) => ({ ...value, boot: false }));
      }
    }
    boot();
  }, []);

  useEffect(() => {
    if (currentUser) loadConsoleData(true);
  }, [currentUser, loadConsoleData]);

  useEffect(() => {
    if (!selectedReportId && reports.length) {
      setSelectedReportId(reports[0].report_id);
    }
  }, [reports, selectedReportId]);

  useEffect(() => {
    if (selectedReportId) loadReportDetail(selectedReportId);
  }, [selectedReportId]);

  async function login(form) {
    try {
      const nextSession = await callApi("/api/auth/login", { method: "POST", body: form });
      setSession(nextSession);
      setCurrentUser(nextSession.user);
      notify("Signed in.");
    } catch (error) {
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
      setSelectedCluster(null);
      setReportDetail(null);
    }
  }

  async function createCluster(form) {
    const cluster = await callApi("/api/clusters", {
      method: "POST",
      body: {
        name: form.name,
        environment: form.environment,
        description: form.description,
      },
    });
    notify("Cluster created.");
    await loadConsoleData(true);
    await generateInstallCommand(cluster.cluster_id, form.backend_url);
    setSelectedCluster(cluster);
    setActiveView("clusters");
  }

  async function generateInstallCommand(clusterId, backendUrl) {
    const params = new URLSearchParams();
    if (backendUrl) params.set("backend_url", backendUrl);
    const suffix = params.toString() ? `?${params}` : "";
    const command = await callApi(`/api/clusters/${encodeURIComponent(clusterId)}/install-command${suffix}`);
    setInstallCommand(command);
    return command;
  }

  async function loadClusterDetail(cluster) {
    if (!cluster) return;
    setSelectedCluster(cluster);
    setActiveView("clusters");
    const clusterId = cluster.cluster_id;
    const [agents, evidence, topology] = await Promise.allSettled([
      callApi(`/api/clusters/${encodeURIComponent(clusterId)}/agent-health`),
      callApi(`/api/clusters/${encodeURIComponent(clusterId)}/evidence-requests?limit=100`),
      callApi(`/api/clusters/${encodeURIComponent(clusterId)}/topology`),
    ]);
    setClusterDetail({
      agents: arrayResult(agents),
      evidence: arrayResult(evidence),
      topology: topology.status === "fulfilled" ? topology.value : null,
    });
  }

  async function deleteCluster(cluster, confirmName) {
    const query = new URLSearchParams({ confirm_name: confirmName });
    await callApi(`/api/clusters/${encodeURIComponent(cluster.cluster_id)}?${query}`, { method: "DELETE" });
    setDeleteDialog(null);
    setSelectedCluster(null);
    setClusterDetail(null);
    notify("Cluster deleted.");
    await loadConsoleData(true);
  }

  async function rotateAgentToken(cluster) {
    const result = await callApi(`/api/clusters/${encodeURIComponent(cluster.cluster_id)}/agent-token/rotate`, {
      method: "POST",
    });
    notify("Agent token rotated.");
    setInstallCommand({
      cluster_id: cluster.cluster_id,
      namespace: "cluster-infra-rca",
      commands: [`New agent token: ${result.agent_token}`],
      notes: [result.note],
    });
  }

  async function startCollection(cluster, nodeName = "") {
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
    notify("Evidence collection requested.");
    await loadClusterDetail(cluster);
  }

  async function loadReportDetail(reportId) {
    try {
      const report = await callApi(`/api/rca/reports/${encodeURIComponent(reportId)}`);
      const incidentId = report.incident_id;
      const requests = [
        callApi(`/api/rca/action-requests?report_id=${encodeURIComponent(reportId)}`),
        ["admin", "operator", "auditor"].includes(currentUser?.role)
          ? callApi(`/api/rca/action-executions?report_id=${encodeURIComponent(reportId)}`)
          : Promise.resolve([]),
        incidentId ? callApi(`/api/rca/incidents/${encodeURIComponent(incidentId)}/timeline`) : Promise.resolve(null),
        ["admin", "operator"].includes(currentUser?.role)
          ? callApi(`/api/rca/reports/${encodeURIComponent(reportId)}/bundle/manifest`)
          : Promise.resolve(null),
      ];
      const [actionReq, executions, timeline, bundleManifest] = await Promise.allSettled(requests);
      setReportDetail({
        report,
        actionRequests: arrayResult(actionReq),
        actionExecutions: arrayResult(executions),
        timeline: timeline.status === "fulfilled" ? timeline.value : null,
        bundleManifest: bundleManifest.status === "fulfilled" ? bundleManifest.value : null,
      });
    } catch (error) {
      notify(error.message || "Failed to load report.", "danger");
    }
  }

  async function executeRecommendedAction(report, actionIndex, note) {
    const response = await callApi(
      `/api/rca/reports/${encodeURIComponent(report.report_id)}/actions/${actionIndex}/execute`,
      { method: "POST", body: { confirmed: true, note } },
    );
    setActionDialog(null);
    notify(response.message || "Action request updated.");
    await loadReportDetail(report.report_id);
    await loadConsoleData(true);
  }

  async function decideActionRequest(actionRequest, decision, note = "") {
    await callApi(`/api/rca/action-requests/${encodeURIComponent(actionRequest.action_request_id)}/${decision}`, {
      method: "POST",
      body: { confirmed: true, note },
    });
    notify(`Action request ${decision}.`);
    await loadReportDetail(actionRequest.report_id);
    await loadConsoleData(true);
  }

  async function completeManualAction(actionRequest, note) {
    await callApi(`/api/rca/action-requests/${encodeURIComponent(actionRequest.action_request_id)}/complete-manual`, {
      method: "POST",
      body: { confirmed: true, note },
    });
    notify("Manual handling completed.");
    await loadReportDetail(actionRequest.report_id);
    await loadConsoleData(true);
  }

  async function changeIncidentStatus(incident, nextStatus) {
    await callApi(`/api/rca/incidents/${encodeURIComponent(incident.incident_id)}/${nextStatus}`, {
      method: "POST",
      body: { confirmed: true, note: "Updated from Web Console." },
    });
    notify(`Incident ${nextStatus}.`);
    await loadConsoleData(true);
  }

  async function retryAnalysisTask(task) {
    await callApi(`/api/rca/analysis-tasks/${encodeURIComponent(task.task_id)}/retry`, {
      method: "POST",
      body: { confirmed: true, note: "Retry requested from Web Console." },
    });
    notify("Analysis task requeued.");
    await loadConsoleData(true);
  }

  async function runDemoScenario(scenario, clusterId, nodeName) {
    await callApi(`/api/demo/scenarios/${encodeURIComponent(scenario.key)}/run`, {
      method: "POST",
      body: { confirmed: true, cluster_id: clusterId || null, node_name: nodeName || null },
    });
    notify("Demo scenario started.");
    await loadConsoleData(true);
  }

  async function changePassword(form) {
    await callApi("/api/auth/change-password", {
      method: "POST",
      body: { current_password: form.current_password, new_password: form.new_password },
    });
    notify(t("Password changed."));
  }

  async function changeLoginId(form) {
    const updatedUser = await callApi("/api/auth/change-login-id", {
      method: "POST",
      body: { current_password: form.current_password, new_username: form.new_username },
    });
    setCurrentUser(updatedUser);
    notify(t("Login ID changed."));
  }

  async function exportReports(clusterId = "") {
    const suffix = clusterId ? `?cluster_id=${encodeURIComponent(clusterId)}` : "";
    await downloadApi(`/api/rca/reports/export${suffix}`, clusterId ? `rca-reports-${clusterId}.json` : "rca-reports.json");
    notify("Export downloaded.");
  }

  async function exportReport(reportId) {
    await downloadApi(`/api/rca/reports/${encodeURIComponent(reportId)}/export`, `rca-report-${reportId}.json`);
    notify("Report exported.");
  }

  async function exportEvidenceBundle(reportId) {
    await downloadApi(`/api/rca/reports/${encodeURIComponent(reportId)}/bundle`, `rca-evidence-bundle-${reportId}.zip`);
    notify(t("Evidence bundle downloaded."));
  }

  async function exportAudit(format = "json", filters = {}) {
    const query = buildAuditQuery({ ...filters, format, limit: 5000 });
    await downloadApi(`/api/audit/events/export?${query}`, `audit-events.${format}`);
    notify("Audit export downloaded.");
  }

  if (loading.boot) {
    return <BootScreen />;
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
          loading={loading.data}
          t={t}
        />
        <main className="console-content">
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
              onOpenCluster={loadClusterDetail}
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
              onSelect={loadClusterDetail}
              onGenerateInstall={generateInstallCommand}
              onStartCollection={startCollection}
              onDelete={(cluster) => setDeleteDialog({ cluster })}
              onRotateToken={rotateAgentToken}
              onCopy={(text) => copyText(text, notify)}
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
              onCopy={(text) => copyText(text, notify)}
              t={t}
            />
          )}
          {activeView === "incidents" && (
            <IncidentsView
              incidents={incidents}
              onOpenReport={(id) => {
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
              onSearch={async (filters) => {
                const query = buildAuditQuery(filters);
                const next = await callApi(`/api/audit/events?${query}`);
                setAuditEvents(sortByTime(next, "created_at"));
              }}
              onExport={exportAudit}
              t={t}
            />
          )}
          {activeView === "webhooks" && (
            <WebhooksView endpoint={webhookEndpoint} onCopy={(text) => copyText(text, notify)} t={t} />
          )}
          {activeView === "settings" && (
            <SettingsView
              locale={locale}
              setLocale={setLocale}
              platformInfo={platformInfo}
              currentUser={currentUser}
              onChangeLoginId={changeLoginId}
              onChangePassword={changePassword}
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

const root = createRoot(document.getElementById("rca-console-root"));
root.render(<ConsoleApp />);
