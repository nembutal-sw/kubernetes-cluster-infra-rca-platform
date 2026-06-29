// @ts-nocheck
import React, { useCallback, useEffect, useState } from "react";
import { createRoot } from "react-dom/client";
import "bootstrap/dist/css/bootstrap.min.css";
import "bootstrap-icons/font/bootstrap-icons.css";
import "./styles.css";

const STORAGE_KEYS = {
  locale: "rca_console_language",
};

const NAV_ITEMS = [
  { id: "overview", label: "Overview", icon: "speedometer2" },
  { id: "clusters", label: "Clusters", icon: "hdd-network" },
  { id: "reports", label: "RCA Reports", icon: "clipboard2-pulse" },
  { id: "incidents", label: "Incidents", icon: "exclamation-diamond" },
  { id: "pipeline", label: "Pipeline", icon: "diagram-3" },
  { id: "audit", label: "Audit", icon: "journal-check", roles: ["admin", "auditor"] },
  { id: "webhooks", label: "Webhooks", icon: "broadcast-pin" },
  { id: "settings", label: "Settings", icon: "gear" },
];

const KO = {
  Overview: "개요",
  Clusters: "클러스터",
  "RCA Reports": "RCA 보고서",
  Incidents: "인시던트",
  Pipeline: "파이프라인",
  Audit: "감사로그",
  Webhooks: "웹훅",
  Settings: "설정",
  "Cluster Infra RCA": "클러스터 인프라 RCA",
  "Linux and Kubernetes infrastructure root cause console": "Linux 및 Kubernetes 인프라 원인 분석 콘솔",
  "Sign in": "로그인",
  Username: "계정",
  Password: "비밀번호",
  "Initial account": "초기 계정",
  "Invalid username or password": "계정 또는 비밀번호가 올바르지 않습니다",
  Logout: "로그아웃",
  Refresh: "새로고침",
  "Open incidents": "진행 중 인시던트",
  "RCA reports": "RCA 보고서",
  "Registered clusters": "등록 클러스터",
  "Healthy agents": "정상 에이전트",
  "Policy blocked": "정책 차단",
  "APM Failure Surface": "APM 장애 표면",
  "Failure propagation": "장애 전파",
  "Signal stream": "시그널 스트림",
  "Cluster topology": "클러스터 토폴로지",
  "Recent RCA": "최근 RCA",
  "No reports loaded.": "불러온 보고서가 없습니다.",
  "No incidents loaded.": "불러온 인시던트가 없습니다.",
  "No clusters registered.": "등록된 클러스터가 없습니다.",
  "No audit events loaded.": "감사 이벤트가 없습니다.",
  "No action requests.": "조치 요청이 없습니다.",
  "No agents registered.": "등록된 에이전트가 없습니다.",
  "Create cluster": "클러스터 생성",
  "Cluster name": "클러스터 이름",
  Environment: "환경",
  Description: "설명",
  "Backend URL": "백엔드 URL",
  "Generate install command": "설치 명령 생성",
  "Install command": "설치 명령",
  Copy: "복사",
  "Copied.": "복사했습니다.",
  Delete: "삭제",
  "Delete cluster": "클러스터 삭제",
  "Type the cluster name to confirm deletion.": "삭제하려면 클러스터 이름을 입력하세요.",
  Cancel: "취소",
  Confirm: "확인",
  "Manual collection": "수동 수집",
  "Collect evidence": "증거 수집",
  Agents: "에이전트",
  Evidence: "증거",
  Topology: "토폴로지",
  Status: "상태",
  Node: "노드",
  Version: "버전",
  "Last heartbeat": "마지막 하트비트",
  "Alert name": "알림명",
  "Created at": "생성일",
  "Completed at": "완료일",
  "Report detail": "보고서 상세",
  "Root cause candidates": "원인 후보",
  "Evidence summary": "근거 요약",
  "Additional checks": "추가 확인 명령",
  "Recommended actions": "권장 조치",
  "Policy gate": "정책 게이트",
  "Automation": "자동화",
  "Risk factors": "위험 사유",
  "Command preview": "명령 미리보기",
  "Request action": "조치 요청",
  "Approve": "승인",
  "Reject": "거절",
  "Complete manual": "수동 처리 완료",
  "Manual workflow": "수동 처리",
  "LLM diagnostic only": "LLM 진단 전용",
  "Rule based": "Rule 기반",
  "Automation blocked": "자동화 차단",
  "Export report": "보고서 내보내기",
  "Export all": "전체 내보내기",
  "Cascading timeline": "장애 전파 타임라인",
  "Action requests": "조치 요청",
  "Analysis tasks": "분석 작업",
  "Demo scenarios": "데모 시나리오",
  Run: "실행",
  Retry: "재시도",
  "Alertmanager endpoint": "Alertmanager 엔드포인트",
  "Receiver sample": "Receiver 예시",
  "Audit search": "감사 검색",
  Search: "검색",
  Export: "내보내기",
  "Client IP": "클라이언트 IP",
  Actor: "행위자",
  Event: "이벤트",
  Resource: "리소스",
  Outcome: "결과",
  Details: "상세",
  Language: "언어",
  English: "영어",
  Korean: "한국어",
  "Change password": "비밀번호 변경",
  "Current password": "현재 비밀번호",
  "New password": "새 비밀번호",
  Save: "저장",
  "Platform info": "플랫폼 정보",
  open: "진행 중",
  resolved: "해결됨",
  healthy: "정상",
  degraded: "저하",
  offline: "오프라인",
  stale: "지연",
  registered: "등록됨",
  active: "활성",
  agent_pending: "에이전트 대기",
  completed: "완료",
  failed: "실패",
  queued: "대기",
  processing: "처리 중",
  retry_wait: "재시도 대기",
  dead_letter: "실패 보관",
  pending_approval: "승인 대기",
  approved_manual: "수동 승인",
  rejected: "거절됨",
  blocked: "차단됨",
  accepted: "접수됨",
  AUTO_SAFE: "자동 안전",
  APPROVAL_REQUIRED: "승인 필요",
  GITOPS_PR_ONLY: "GitOps PR 전용",
  NEVER_AUTO_EXECUTE: "자동 실행 금지",
  MANUAL_INVESTIGATION: "수동 조사",
};

const POLICY_HELP = {
  AUTO_SAFE: "Read-only evidence collection can be requested from the agent.",
  APPROVAL_REQUIRED: "A human approval record is required. Execution remains manual.",
  GITOPS_PR_ONLY: "Use a GitOps PR or runbook. Do not execute directly from the console.",
  NEVER_AUTO_EXECUTE: "High-risk remediation. The console only records review decisions.",
  MANUAL_INVESTIGATION: "Operator investigation is required before any remediation.",
};

const SIGNAL_STAGES = [
  { key: "disk", label: "Disk I/O", icon: "nvme" },
  { key: "runtime", label: "Runtime", icon: "boxes" },
  { key: "kubelet", label: "Kubelet", icon: "cpu" },
  { key: "network", label: "Network", icon: "ethernet" },
  { key: "control", label: "Control Plane", icon: "diagram-2" },
  { key: "service", label: "Service Impact", icon: "activity" },
];

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

  const authHeaders = useCallback(() => {
    const token = session?.access_token || session?.accessToken;
    return token ? { Authorization: `Bearer ${token}` } : {};
  }, [session]);

  const callApi = useCallback(async (path, options = {}) => {
    const headers = {
      Accept: "application/json",
      ...authHeaders(),
      ...(options.body ? { "Content-Type": "application/json" } : {}),
      ...(options.headers || {}),
    };
    const response = await fetch(path, {
      method: options.method || "GET",
      credentials: "same-origin",
      headers,
      body: options.body ? JSON.stringify(options.body) : undefined,
    });
    if (!response.ok) {
      const text = await response.text();
      throw new Error(text || `${response.status} ${response.statusText}`);
    }
    if (response.status === 204) return null;
    const contentType = response.headers.get("content-type") || "";
    return contentType.includes("application/json") ? response.json() : response.text();
  }, [authHeaders]);

  const downloadApi = useCallback(async (path, filename) => {
    const response = await fetch(path, {
      credentials: "same-origin",
      headers: { ...authHeaders() },
    });
    if (!response.ok) {
      throw new Error(await response.text());
    }
    const blob = await response.blob();
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement("a");
    anchor.href = url;
    anchor.download = filename;
    document.body.appendChild(anchor);
    anchor.click();
    anchor.remove();
    URL.revokeObjectURL(url);
  }, [authHeaders]);

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
      setClusters(arrayResult(results[0]));
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
    } catch (error) {
      notify(error.message || "Failed to load console data.", "danger");
    } finally {
      setLoading((value) => ({ ...value, data: false }));
    }
  }, [callApi, currentUser?.role, notify]);

  useEffect(() => {
    async function boot() {
      try {
        const response = await fetch("/api/auth/me", {
          credentials: "same-origin",
          headers: { Accept: "application/json" },
        });
        if (!response.ok) {
          throw new Error("not authenticated");
        }
        const user = await response.json();
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
      ];
      const [actionReq, executions, timeline] = await Promise.allSettled(requests);
      setReportDetail({
        report,
        actionRequests: arrayResult(actionReq),
        actionExecutions: arrayResult(executions),
        timeline: timeline.status === "fulfilled" ? timeline.value : null,
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
    notify("Password changed.");
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
              onExportAll={() => exportReports()}
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

function BootScreen() {
  return (
    <div className="boot-screen">
      <div className="boot-mark">
        <Icon name="activity" />
      </div>
      <div>
        <strong>Cluster Infra RCA</strong>
        <p>Loading console</p>
      </div>
    </div>
  );
}

function LoginPage({ onLogin, locale, setLocale, t, toast }) {
  const [form, setForm] = useState({ username: "admin", password: "" });
  const [busy, setBusy] = useState(false);
  async function submit(event) {
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
      <section className="login-panel">
        <div className="brand-row">
          <div className="brand-icon"><Icon name="activity" /></div>
          <div>
            <h1>{t("Cluster Infra RCA")}</h1>
            <p>{t("Linux and Kubernetes infrastructure root cause console")}</p>
          </div>
        </div>
        <div className="login-observability">
          <div><span>Disk I/O</span><strong>watch</strong></div>
          <div><span>Kubelet</span><strong>rule gate</strong></div>
          <div><span>Network</span><strong>timeline</strong></div>
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
      {toast && <Toast tone={toast.tone} message={toast.message} />}
    </div>
  );
}

function Sidebar({ items, activeView, setActiveView, t }) {
  return (
    <aside className="console-sidebar">
      <div className="sidebar-brand">
        <div className="brand-icon"><Icon name="activity" /></div>
        <div>
          <strong>Infra RCA</strong>
          <span>APM Console</span>
        </div>
      </div>
      <nav className="sidebar-nav" aria-label="Console navigation">
        {items.map((item) => (
          <button
            key={item.id}
            type="button"
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

function Topbar({ user, locale, setLocale, onRefresh, onLogout, loading, t }) {
  return (
    <header className="console-topbar">
      <div>
        <div className="topbar-eyebrow">Cluster Infra RCA</div>
        <h2>{t("Linux and Kubernetes infrastructure root cause console")}</h2>
      </div>
      <div className="topbar-actions">
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

function OverviewView({ clusters, reports, incidents, analysisTasks, actionRequests, onNavigate, onOpenReport, onOpenCluster, webhookEndpoint, t }) {
  const openIncidents = incidents.filter((item) => item.status === "open");
  const agents = clusters.reduce((acc, cluster) => acc + Number(cluster.agent_count || 0), 0);
  const blockedActions = reports.flatMap((report) => report.recommended_actions || []).filter((action) => action.automation_allowed !== true).length;
  const signalDigest = buildSignalDigest(reports, incidents);
  const latestReport = reports[0];
  return (
    <div className="page-stack">
      <section className="apm-hero">
        <div>
          <p className="section-kicker">APM-style infrastructure lens</p>
          <h1>{t("APM Failure Surface")}</h1>
          <p>
            Node pressure, kernel/runtime evidence, control-plane latency, and policy-gated remediation in one operational surface.
          </p>
        </div>
        <div className="hero-actions">
          <button className="btn btn-light btn-sm icon-button" onClick={() => onNavigate("reports")}>
            <Icon name="clipboard2-pulse" />
            <span>{t("RCA Reports")}</span>
          </button>
          <button className="btn btn-outline-light btn-sm icon-button" onClick={() => onNavigate("clusters")}>
            <Icon name="hdd-network" />
            <span>{t("Clusters")}</span>
          </button>
        </div>
      </section>

      <section className="metric-grid">
        <MetricTile label={t("Open incidents")} value={openIncidents.length} tone={openIncidents.length ? "red" : "green"} icon="exclamation-diamond" />
        <MetricTile label={t("RCA reports")} value={reports.length} tone="blue" icon="clipboard2-pulse" />
        <MetricTile label={t("Registered clusters")} value={clusters.length} tone="teal" icon="hdd-network" />
        <MetricTile label={t("Policy blocked")} value={blockedActions} tone={blockedActions ? "amber" : "green"} icon="shield-lock" />
      </section>

      <div className="dashboard-grid">
        <Surface title={t("Failure propagation")} subtitle="Evidence sequence by system layer" action={<button className="btn btn-sm btn-outline-secondary" onClick={() => onNavigate("reports")}>{t("RCA Reports")}</button>}>
          <FailureSurface reports={reports} incidents={incidents} t={t} />
        </Surface>
        <Surface title={t("Signal stream")} subtitle="Prioritized recent infrastructure signals">
          <SignalStream items={signalDigest} t={t} />
        </Surface>
        <Surface title={t("Cluster topology")} subtitle="Registration and agent posture">
          <ClusterTopologyPreview clusters={clusters} onOpenCluster={onOpenCluster} t={t} />
        </Surface>
        <Surface title={t("Recent RCA")} subtitle={latestReport ? latestReport.report_id : "No report selected"} action={<button className="btn btn-sm btn-outline-secondary" onClick={() => onNavigate("reports")}>Open</button>}>
          <RecentReport report={latestReport} onOpenReport={onOpenReport} t={t} />
        </Surface>
      </div>

      <section className="ops-strip">
        <div>
          <span>Webhook</span>
          <strong>{webhookEndpoint}</strong>
        </div>
        <div>
          <span>Pipeline backlog</span>
          <strong>{analysisTasks.filter((task) => ["queued", "processing", "retry_wait"].includes(task.status)).length}</strong>
        </div>
        <div>
          <span>{t("Action requests")}</span>
          <strong>{actionRequests.length}</strong>
        </div>
        <div>
          <span>{t("Healthy agents")}</span>
          <strong>{agents || "n/a"}</strong>
        </div>
      </section>
    </div>
  );
}

function ClustersView(props) {
  const {
    clusters,
    selectedCluster,
    clusterDetail,
    installCommand,
    currentUser,
    onCreate,
    onSelect,
    onGenerateInstall,
    onStartCollection,
    onDelete,
    onRotateToken,
    onCopy,
    t,
  } = props;
  const canOperate = ["admin", "operator"].includes(currentUser.role);
  return (
    <div className="page-stack">
      <PageHeader title={t("Clusters")} subtitle="Register clusters, install node agents, and inspect collected evidence." />
      <div className="split-grid">
        <Surface title={t("Create cluster")} subtitle="Minimal registration flow">
          <ClusterForm onCreate={onCreate} disabled={!canOperate} t={t} />
          {installCommand && <InstallCommand command={installCommand} onCopy={onCopy} t={t} />}
        </Surface>
        <Surface title={t("Cluster topology")} subtitle={`${clusters.length} registered`}>
          <ClusterList clusters={clusters} selectedCluster={selectedCluster} onSelect={onSelect} onGenerateInstall={onGenerateInstall} onDelete={onDelete} onRotateToken={onRotateToken} canOperate={canOperate} currentUser={currentUser} t={t} />
        </Surface>
      </div>
      {selectedCluster && (
        <Surface title={selectedCluster.name} subtitle={`${selectedCluster.cluster_id} / ${selectedCluster.environment}`}>
          <ClusterDetail cluster={selectedCluster} detail={clusterDetail} onStartCollection={onStartCollection} canOperate={canOperate} t={t} />
        </Surface>
      )}
    </div>
  );
}

function ClusterForm({ onCreate, disabled, t }) {
  const [form, setForm] = useState({
    name: "",
    environment: "dev",
    description: "",
    backend_url: window.location.origin,
  });
  const [busy, setBusy] = useState(false);
  async function submit(event) {
    event.preventDefault();
    setBusy(true);
    try {
      await onCreate(form);
      setForm({ ...form, name: "", description: "" });
    } finally {
      setBusy(false);
    }
  }
  return (
    <form className="cluster-form" onSubmit={submit}>
      <label>{t("Cluster name")}<input className="form-control" value={form.name} disabled={disabled} required onChange={(event) => setForm({ ...form, name: event.target.value })} /></label>
      <label>{t("Environment")}<select className="form-select" value={form.environment} disabled={disabled} onChange={(event) => setForm({ ...form, environment: event.target.value })}><option>dev</option><option>stage</option><option>prod</option><option>dr</option></select></label>
      <label className="wide">{t("Description")}<textarea className="form-control" rows={2} value={form.description} disabled={disabled} onChange={(event) => setForm({ ...form, description: event.target.value })} /></label>
      <label className="wide">{t("Backend URL")}<input className="form-control" value={form.backend_url} disabled={disabled} onChange={(event) => setForm({ ...form, backend_url: event.target.value })} /></label>
      <button className="btn btn-primary icon-button" disabled={disabled || busy || !form.name.trim()}>
        <Icon name="plus-circle" />
        <span>{busy ? "..." : t("Generate install command")}</span>
      </button>
    </form>
  );
}

function ClusterList({ clusters, selectedCluster, onSelect, onGenerateInstall, onDelete, onRotateToken, canOperate, currentUser, t }) {
  if (!clusters.length) return <EmptyState message={t("No clusters registered.")} />;
  return (
    <div className="cluster-list">
      {clusters.map((cluster) => (
        <article key={cluster.cluster_id} className={`cluster-row ${selectedCluster?.cluster_id === cluster.cluster_id ? "selected" : ""}`}>
          <button type="button" className="cluster-main" onClick={() => onSelect(cluster)}>
            <div className="cluster-node-icon"><Icon name="hdd-network" /></div>
            <div>
              <strong>{cluster.name}</strong>
              <span>{cluster.cluster_id}</span>
            </div>
          </button>
          <div className="cluster-meta">
            <StatusBadge value={cluster.status} tone={cluster.status === "active" ? "green" : "amber"} t={t} />
            <span>{cluster.environment}</span>
          </div>
          <div className="row-actions">
            {canOperate && <button className="btn btn-sm btn-outline-secondary" onClick={() => onGenerateInstall(cluster.cluster_id, window.location.origin)}>{t("Install command")}</button>}
            {currentUser.role === "admin" && <button className="btn btn-sm btn-outline-secondary" onClick={() => onRotateToken(cluster)}><Icon name="arrow-repeat" /></button>}
            {currentUser.role === "admin" && <button className="btn btn-sm btn-outline-danger" onClick={() => onDelete(cluster)}><Icon name="trash" /></button>}
          </div>
        </article>
      ))}
    </div>
  );
}

function InstallCommand({ command, onCopy, t }) {
  const commandText = (command.commands || []).join("\n");
  return (
    <div className="install-command">
      <div className="install-head">
        <strong>{t("Install command")}</strong>
        <button className="btn btn-sm btn-outline-secondary icon-button" onClick={() => onCopy(commandText)}>
          <Icon name="clipboard" /><span>{t("Copy")}</span>
        </button>
      </div>
      <pre>{commandText}</pre>
      {(command.notes || []).map((note) => <p key={note} className="note-line">{note}</p>)}
    </div>
  );
}

function ClusterDetail({ cluster, detail, onStartCollection, canOperate, t }) {
  const agents = detail?.agents || [];
  const evidence = detail?.evidence || [];
  const topology = detail?.topology || {};
  const entities = topology.entities || [];
  return (
    <div className="cluster-detail-grid">
      <div>
        <div className="section-toolbar">
          <h3>{t("Agents")}</h3>
          {canOperate && <button className="btn btn-sm btn-primary icon-button" onClick={() => onStartCollection(cluster)}><Icon name="collection" /><span>{t("Collect evidence")}</span></button>}
        </div>
        <ResponsiveTable
          empty={t("No agents registered.")}
          columns={[t("Node"), t("Status"), t("Version"), t("Last heartbeat")]}
          rows={agents.map((agent) => [
            agent.node_name,
            <StatusBadge value={agent.health_status || agent.status || agent.reported_status} tone={agentHealthTone(agent)} t={t} />,
            agent.agent_version || "n/a",
            relativeTime(agent.last_heartbeat_at),
          ])}
        />
      </div>
      <div>
        <h3>{t("Evidence")}</h3>
        <div className="evidence-list">
          {evidence.length ? evidence.slice(0, 8).map((item) => (
            <article key={item.request_id} className="evidence-item">
              <strong>{item.alert_name}</strong>
              <span>{item.node_name}</span>
              <StatusBadge value={item.status} tone={item.status === "completed" ? "green" : item.status === "failed" ? "red" : "amber"} t={t} />
            </article>
          )) : <EmptyState message="No evidence requests." />}
        </div>
      </div>
      <div className="wide">
        <h3>{t("Topology")}</h3>
        <div className="topology-entities">
          {entities.slice(0, 18).map((entity) => (
            <span key={entity.id} className="entity-pill">{entity.kind}/{entity.name}</span>
          ))}
          {!entities.length && <span className="text-muted">Topology observation is not loaded yet.</span>}
        </div>
      </div>
    </div>
  );
}

function ReportsView({ reports, selectedReportId, setSelectedReportId, detail, currentUser, onPrepareAction, onDecideAction, onCompleteManual, onExportReport, onExportAll, t }) {
  const canExport = ["admin", "operator"].includes(currentUser.role);
  return (
    <div className="page-stack">
      <PageHeader
        title={t("RCA Reports")}
        subtitle="Root cause candidates, evidence, policy gates, and operator workflow."
        actions={canExport && <button className="btn btn-sm btn-outline-secondary icon-button" onClick={onExportAll}><Icon name="download" /><span>{t("Export all")}</span></button>}
      />
      <div className="report-layout">
        <aside className="report-list">
          {reports.length ? reports.map((report) => (
            <button key={report.report_id} className={selectedReportId === report.report_id ? "selected" : ""} onClick={() => setSelectedReportId(report.report_id)}>
              <span className="report-time">{relativeTime(report.created_at)}</span>
              <strong>{report.summary?.symptom || report.trigger?.alert_name || report.report_id}</strong>
              <span>{report.cluster_id} / {report.node_name || "cluster"}</span>
            </button>
          )) : <EmptyState message={t("No reports loaded.")} />}
        </aside>
        <section className="report-detail-panel">
          {detail?.report ? (
            <ReportDetail
              detail={detail}
              currentUser={currentUser}
              onPrepareAction={onPrepareAction}
              onDecideAction={onDecideAction}
              onCompleteManual={onCompleteManual}
              onExportReport={onExportReport}
              t={t}
            />
          ) : <EmptyState message="Select an RCA report." />}
        </section>
      </div>
    </div>
  );
}

function ReportDetail({ detail, currentUser, onPrepareAction, onDecideAction, onCompleteManual, onExportReport, t }) {
  const report = detail.report;
  const candidates = report.root_cause_candidates || [];
  const actions = report.recommended_actions || [];
  const checks = report.additional_checks || report.next_steps || [];
  const evidenceItems = evidenceSummary(report);
  const llmActions = actions.filter((action) => action.source === "llm");
  return (
    <div className="report-detail">
      <div className="report-detail-head">
        <div>
          <p className="section-kicker">{report.report_id}</p>
          <h2>{report.summary?.most_likely_cause || report.summary?.symptom || "RCA report"}</h2>
          <div className="meta-row">
            <span>{report.cluster_id}</span>
            <span>{report.node_name || "cluster scope"}</span>
            <span>{formatDate(report.created_at)}</span>
          </div>
        </div>
        {["admin", "operator"].includes(currentUser.role) && (
          <button className="btn btn-sm btn-outline-secondary icon-button" onClick={() => onExportReport(report.report_id)}>
            <Icon name="download" /><span>{t("Export report")}</span>
          </button>
        )}
      </div>

      <div className="summary-strip">
        <MetricTile label="Confidence" value={report.summary?.confidence || "n/a"} tone={confidenceTone(report.summary?.confidence)} icon="bar-chart-line" />
        <MetricTile label={t("Policy blocked")} value={actions.filter((action) => !action.automation_allowed).length} tone="amber" icon="shield-lock" />
        <MetricTile label="LLM" value={llmActions.length ? t("LLM diagnostic only") : "n/a"} tone={llmActions.length ? "amber" : "muted"} icon="stars" />
      </div>

      {llmActions.length > 0 && (
        <div className="policy-warning">
          <Icon name="shield-exclamation" />
          <span>{t("LLM diagnostic only")}: LLM-origin actions stay automation_allowed=false. Operators can create a request, record approval/rejection, or mark manual handling complete.</span>
        </div>
      )}

      <Surface title={t("Cascading timeline")} subtitle="Observed evidence order and inferred propagation">
        <TimelineGraph timeline={detail.timeline} report={report} t={t} />
      </Surface>

      <div className="detail-grid">
        <Surface title={t("Root cause candidates")} subtitle="Ranked by rule and evidence confidence">
          <CandidateList candidates={candidates} t={t} />
        </Surface>
        <Surface title={t("Evidence summary")} subtitle="Signals used by analyzer">
          <EvidenceSummary items={evidenceItems} t={t} />
        </Surface>
        <Surface title={t("Additional checks")} subtitle="Commands to verify before remediation">
          <CheckList checks={checks} />
        </Surface>
        <Surface title={t("Policy gate")} subtitle="Policy Engine result before any action request">
          <PolicySummary actions={actions} t={t} />
        </Surface>
      </div>

      <Surface title={t("Recommended actions")} subtitle="Every action remains behind policy and human confirmation">
        <ActionList report={report} actions={actions} onPrepareAction={onPrepareAction} t={t} />
      </Surface>

      <Surface title={t("Action requests")} subtitle="Approval records and manual completion">
        <ActionRequestList
          items={detail.actionRequests}
          executions={detail.actionExecutions}
          currentUser={currentUser}
          onDecideAction={onDecideAction}
          onCompleteManual={onCompleteManual}
          t={t}
        />
      </Surface>
    </div>
  );
}

function CandidateList({ candidates, t }) {
  if (!candidates.length) return <EmptyState message="No root cause candidates." />;
  return (
    <div className="candidate-list">
      {candidates.map((candidate, index) => (
        <article key={`${candidate.cause}-${index}`} className="candidate-item">
          <div className="candidate-score" style={{ "--score": `${candidate.confidence_score || 0}%` }}>
            <strong>{candidate.confidence_score || 0}</strong>
            <span>%</span>
          </div>
          <div>
            <strong>{candidate.cause}</strong>
            <div className="supporting-lines">
              {(candidate.supporting_evidence || []).slice(0, 3).map((line) => <span key={line}>{line}</span>)}
            </div>
          </div>
        </article>
      ))}
    </div>
  );
}

function EvidenceSummary({ items }) {
  if (!items.length) return <EmptyState message="No evidence summary." />;
  return (
    <div className="signal-list compact">
      {items.slice(0, 10).map((item, index) => (
        <article key={`${item.label}-${index}`} className="signal-row">
          <Icon name={signalIcon(item.label)} />
          <div>
            <strong>{item.label}</strong>
            <span>{item.value}</span>
          </div>
        </article>
      ))}
    </div>
  );
}

function CheckList({ checks }) {
  const normalized = Array.isArray(checks) ? checks : [];
  if (!normalized.length) return <EmptyState message="No additional checks." />;
  return (
    <div className="command-list">
      {normalized.map((item, index) => {
        const command = typeof item === "string" ? item : item.command || item.description || JSON.stringify(item);
        return <pre key={`${command}-${index}`}>{command}</pre>;
      })}
    </div>
  );
}

function PolicySummary({ actions, t }) {
  if (!actions.length) return <EmptyState message="No policy decisions." />;
  const counts = actions.reduce((acc, action) => {
    const key = action.policy || "UNKNOWN";
    acc[key] = (acc[key] || 0) + 1;
    return acc;
  }, {});
  return (
    <div className="policy-grid">
      {Object.entries(counts).map(([policy, count]) => (
        <article key={policy} className={`policy-tile ${policyTone(policy)}`}>
          <StatusBadge value={policy} tone={policyTone(policy)} t={t} />
          <strong>{count}</strong>
          <span>{POLICY_HELP[policy] || "Policy decision"}</span>
        </article>
      ))}
    </div>
  );
}

function ActionList({ report, actions, onPrepareAction, t }) {
  if (!actions.length) return <EmptyState message="No recommended actions." />;
  return (
    <div className="action-grid">
      {actions.map((action, index) => {
        const automationBlocked = action.automation_allowed !== true;
        const llm = action.source === "llm";
        return (
          <article key={`${action.action_key}-${index}`} className={`action-card ${automationBlocked ? "blocked" : "allowed"}`}>
            <div className="action-head">
              <StatusBadge value={action.policy} tone={policyTone(action.policy)} t={t} />
              {llm && <span className="llm-pill">{t("LLM diagnostic only")}</span>}
            </div>
            <h3>{action.action}</h3>
            <p>{action.reason}</p>
            <div className="action-meta">
              <span>{t("Automation")}</span>
              <strong>{automationBlocked ? t("Automation blocked") : "read-only collection"}</strong>
            </div>
            {(action.risk_factors || []).length > 0 && (
              <div className="risk-list">
                {(action.risk_factors || []).slice(0, 3).map((risk) => <span key={risk}>{risk}</span>)}
              </div>
            )}
            {action.execution_plan?.command_preview?.length > 0 && (
              <pre className="command-preview">{action.execution_plan.command_preview.join("\n")}</pre>
            )}
            <button className="btn btn-sm btn-primary icon-button" onClick={() => onPrepareAction(report, action, index)}>
              <Icon name={action.automation_allowed ? "collection" : "person-check"} />
              <span>{action.automation_allowed ? t("Collect evidence") : t("Request action")}</span>
            </button>
          </article>
        );
      })}
    </div>
  );
}

function ActionRequestList({ items, executions, currentUser, onDecideAction, onCompleteManual, t }) {
  const [noteById, setNoteById] = useState({});
  if (!items?.length) return <EmptyState message={t("No action requests.")} />;
  return (
    <div className="request-list">
      {items.map((item) => {
        const execution = (executions || []).find((value) => value.action_request_id === item.action_request_id);
        const canApprove = ["admin", "approver"].includes(currentUser.role) && item.status === "pending_approval";
        const canComplete = ["admin", "operator"].includes(currentUser.role) && item.status === "approved_manual";
        return (
          <article key={item.action_request_id} className="request-item">
            <div>
              <strong>{item.action_key}</strong>
              <span>{item.action_request_id}</span>
            </div>
            <StatusBadge value={item.status} tone={requestTone(item.status)} t={t} />
            <div className="request-meta">
              <span>{item.policy}</span>
              <span>{item.source}</span>
              <span>{relativeTime(item.created_at)}</span>
            </div>
            {execution && <pre className="command-preview">{execution.status}: {execution.command_key}</pre>}
            {(canApprove || canComplete) && (
              <div className="request-actions">
                <input className="form-control form-control-sm" placeholder="Decision note" value={noteById[item.action_request_id] || ""} onChange={(event) => setNoteById({ ...noteById, [item.action_request_id]: event.target.value })} />
                {canApprove && <button className="btn btn-sm btn-success" onClick={() => onDecideAction(item, "approve", noteById[item.action_request_id] || "")}>{t("Approve")}</button>}
                {canApprove && <button className="btn btn-sm btn-outline-danger" onClick={() => onDecideAction(item, "reject", noteById[item.action_request_id] || "")}>{t("Reject")}</button>}
                {canComplete && <button className="btn btn-sm btn-primary" disabled={!noteById[item.action_request_id]} onClick={() => onCompleteManual(item, noteById[item.action_request_id])}>{t("Complete manual")}</button>}
              </div>
            )}
          </article>
        );
      })}
    </div>
  );
}

function IncidentsView({ incidents, onOpenReport, onChangeStatus, currentUser, t }) {
  const canOperate = ["admin", "operator"].includes(currentUser.role);
  return (
    <div className="page-stack">
      <PageHeader title={t("Incidents")} subtitle="Correlated evidence grouped by node, cause, and recurrence." />
      <Surface title={t("Incidents")} subtitle={`${incidents.length} total`}>
        <div className="incident-list">
          {incidents.length ? incidents.map((incident) => (
            <article key={incident.incident_id} className="incident-item">
              <div>
                <StatusBadge value={incident.status} tone={incident.status === "open" ? "red" : "green"} t={t} />
                <h3>{incident.alert_name}</h3>
                <p>{incident.root_cause || "Root cause not available yet."}</p>
                <div className="meta-row">
                  <span>{incident.cluster_id}</span>
                  <span>{(incident.node_names || [incident.node_name]).filter(Boolean).join(", ")}</span>
                  <span>{incident.occurrence_count}x</span>
                </div>
              </div>
              <div className="incident-actions">
                {incident.latest_report_id && <button className="btn btn-sm btn-outline-secondary" onClick={() => onOpenReport(incident.latest_report_id)}>{t("RCA Reports")}</button>}
                {canOperate && incident.status === "open" && <button className="btn btn-sm btn-success" onClick={() => onChangeStatus(incident, "resolve")}>Resolve</button>}
                {canOperate && incident.status === "resolved" && <button className="btn btn-sm btn-outline-secondary" onClick={() => onChangeStatus(incident, "reopen")}>Reopen</button>}
              </div>
            </article>
          )) : <EmptyState message={t("No incidents loaded.")} />}
        </div>
      </Surface>
    </div>
  );
}

function PipelineView({ tasks, actionRequests, demoScenarios, clusters, onRetry, onRunDemo, t }) {
  return (
    <div className="page-stack">
      <PageHeader title={t("Pipeline")} subtitle="Analysis worker, approval queue, and built-in RCA scenario generator." />
      <div className="split-grid">
        <Surface title={t("Analysis tasks")} subtitle={`${tasks.length} tasks`}>
          <TaskList tasks={tasks} onRetry={onRetry} t={t} />
        </Surface>
        <Surface title={t("Action requests")} subtitle={`${actionRequests.length} requests`}>
          <RequestQueue items={actionRequests} t={t} />
        </Surface>
      </div>
      <Surface title={t("Demo scenarios")} subtitle="Generate realistic evidence without Prometheus">
        <DemoScenarios scenarios={demoScenarios} clusters={clusters} onRunDemo={onRunDemo} t={t} />
      </Surface>
    </div>
  );
}

function TaskList({ tasks, onRetry, t }) {
  if (!tasks.length) return <EmptyState message="No analysis tasks." />;
  return (
    <div className="task-list">
      {tasks.slice(0, 30).map((task) => (
        <article key={task.task_id} className="task-item">
          <div>
            <strong>{task.alert_name || task.task_id}</strong>
            <span>{task.cluster_id} / {task.node_name || "cluster"}</span>
          </div>
          <StatusBadge value={task.status} tone={taskTone(task.status)} t={t} />
          {["failed", "dead_letter"].includes(task.status) && <button className="btn btn-sm btn-outline-secondary" onClick={() => onRetry(task)}>{t("Retry")}</button>}
        </article>
      ))}
    </div>
  );
}

function RequestQueue({ items, t }) {
  if (!items.length) return <EmptyState message={t("No action requests.")} />;
  return (
    <div className="task-list">
      {items.slice(0, 30).map((item) => (
        <article key={item.action_request_id} className="task-item">
          <div>
            <strong>{item.action_key}</strong>
            <span>{item.report_id}</span>
          </div>
          <StatusBadge value={item.status} tone={requestTone(item.status)} t={t} />
        </article>
      ))}
    </div>
  );
}

function DemoScenarios({ scenarios, clusters, onRunDemo, t }) {
  const [clusterId, setClusterId] = useState(clusters[0]?.cluster_id || "");
  const [nodeName, setNodeName] = useState("demo-worker-01");
  useEffect(() => {
    if (!clusterId && clusters[0]?.cluster_id) setClusterId(clusters[0].cluster_id);
  }, [clusterId, clusters]);
  if (!scenarios.length) return <EmptyState message="No demo scenarios." />;
  return (
    <div className="scenario-grid">
      {scenarios.map((scenario) => (
        <article key={scenario.key} className="scenario-card">
          <h3>{scenario.name}</h3>
          <p>{scenario.description}</p>
          <div className="scenario-controls">
            <select className="form-select form-select-sm" value={clusterId} onChange={(event) => setClusterId(event.target.value)}>
              <option value="">Auto demo cluster</option>
              {clusters.map((cluster) => <option key={cluster.cluster_id} value={cluster.cluster_id}>{cluster.name}</option>)}
            </select>
            <input className="form-control form-control-sm" value={nodeName} onChange={(event) => setNodeName(event.target.value)} />
            <button className="btn btn-sm btn-primary" onClick={() => onRunDemo(scenario, clusterId, nodeName)}>{t("Run")}</button>
          </div>
        </article>
      ))}
    </div>
  );
}

function AuditView({ events, onSearch, onExport, t }) {
  const [filters, setFilters] = useState({ q: "", client_ip: "", event_type: "", outcome: "", limit: 200 });
  async function submit(event) {
    event.preventDefault();
    await onSearch(filters);
  }
  return (
    <div className="page-stack">
      <PageHeader title={t("Audit")} subtitle="Access, approval, export, agent auth, and administrative records." />
      <Surface title={t("Audit search")} subtitle="Filter by event, IP, actor, outcome, or text">
        <form className="audit-form" onSubmit={submit}>
          <input className="form-control" placeholder="q" value={filters.q} onChange={(event) => setFilters({ ...filters, q: event.target.value })} />
          <input className="form-control" placeholder={t("Client IP")} value={filters.client_ip} onChange={(event) => setFilters({ ...filters, client_ip: event.target.value })} />
          <input className="form-control" placeholder={t("Event")} value={filters.event_type} onChange={(event) => setFilters({ ...filters, event_type: event.target.value })} />
          <input className="form-control" placeholder={t("Outcome")} value={filters.outcome} onChange={(event) => setFilters({ ...filters, outcome: event.target.value })} />
          <button className="btn btn-primary">{t("Search")}</button>
          <button type="button" className="btn btn-outline-secondary" onClick={() => onExport("json", filters)}>JSON</button>
          <button type="button" className="btn btn-outline-secondary" onClick={() => onExport("csv", filters)}>CSV</button>
        </form>
      </Surface>
      <Surface title={t("Audit")} subtitle={`${events.length} events`}>
        <ResponsiveTable
          empty={t("No audit events loaded.")}
          columns={[t("Created at"), t("Actor"), t("Event"), t("Resource"), t("Outcome"), t("Client IP"), t("Details")]}
          rows={events.map((event) => [
            formatDate(event.created_at),
            `${event.actor_type}/${event.actor_id || "-"}`,
            event.event_type,
            `${event.resource_type}/${event.resource_id || "-"}`,
            <StatusBadge value={event.outcome} tone={auditTone(event.outcome)} t={t} />,
            auditClientIp(event),
            <span className="text-break">{auditSummary(event.details)}</span>,
          ])}
        />
      </Surface>
    </div>
  );
}

function WebhooksView({ endpoint, onCopy, t }) {
  const sample = `receivers:
  - name: cluster-infra-rca
    webhook_configs:
      - url: ${endpoint}
        send_resolved: true
        http_config:
          authorization:
            type: Bearer
            credentials_file: /etc/alertmanager/secrets/rca-webhook-token`;
  return (
    <div className="page-stack">
      <PageHeader title={t("Webhooks")} subtitle="Alertmanager is optional; the backend can also request evidence collection directly." />
      <div className="split-grid">
        <Surface title={t("Alertmanager endpoint")} subtitle="Protected by webhook token">
          <div className="endpoint-box">
            <code>{endpoint}</code>
            <button className="btn btn-sm btn-outline-secondary icon-button" onClick={() => onCopy(endpoint)}><Icon name="clipboard" /><span>{t("Copy")}</span></button>
          </div>
        </Surface>
        <Surface title={t("Receiver sample")} subtitle="YAML">
          <pre className="config-sample">{sample}</pre>
          <button className="btn btn-sm btn-outline-secondary icon-button" onClick={() => onCopy(sample)}><Icon name="clipboard" /><span>{t("Copy")}</span></button>
        </Surface>
      </div>
    </div>
  );
}

function SettingsView({ locale, setLocale, platformInfo, onChangePassword, t }) {
  const [password, setPassword] = useState({ current_password: "", new_password: "" });
  async function submit(event) {
    event.preventDefault();
    await onChangePassword(password);
    setPassword({ current_password: "", new_password: "" });
  }
  return (
    <div className="page-stack">
      <PageHeader title={t("Settings")} subtitle="Console preferences and local admin credential rotation." />
      <div className="split-grid">
        <Surface title={t("Language")} subtitle="Preference is stored in this browser">
          <LanguageSwitch locale={locale} setLocale={setLocale} expanded />
        </Surface>
        <Surface title={t("Change password")} subtitle="The built-in admin account should be rotated after install">
          <form className="password-form" onSubmit={submit}>
            <label>{t("Current password")}<input className="form-control" type="password" value={password.current_password} onChange={(event) => setPassword({ ...password, current_password: event.target.value })} /></label>
            <label>{t("New password")}<input className="form-control" type="password" value={password.new_password} onChange={(event) => setPassword({ ...password, new_password: event.target.value })} /></label>
            <button className="btn btn-primary">{t("Save")}</button>
          </form>
        </Surface>
      </div>
      <Surface title={t("Platform info")} subtitle="Protocol compatibility">
        <div className="info-grid">
          {Object.entries(platformInfo || {}).map(([key, value]) => <div key={key}><span>{key}</span><strong>{String(value)}</strong></div>)}
        </div>
      </Surface>
    </div>
  );
}

function FailureSurface({ reports, incidents }) {
  const counts = SIGNAL_STAGES.map((stage) => ({
    ...stage,
    count: scoreStage(stage.key, reports, incidents),
  }));
  const max = Math.max(1, ...counts.map((item) => item.count));
  return (
    <div className="failure-surface">
      {counts.map((stage, index) => (
        <div key={stage.key} className={`surface-stage ${stage.count ? "hot" : ""}`}>
          <div className="stage-icon"><Icon name={stage.icon} /></div>
          <strong>{stage.label}</strong>
          <div className="stage-bar"><span style={{ width: `${Math.max(8, (stage.count / max) * 100)}%` }} /></div>
          <small>{stage.count} signals</small>
          {index < counts.length - 1 && <div className="stage-link" />}
        </div>
      ))}
    </div>
  );
}

function SignalStream({ items, t }) {
  if (!items.length) return <EmptyState message="New node or control-plane evidence will appear here." />;
  return (
    <div className="signal-list">
      {items.slice(0, 8).map((item) => (
        <article key={item.id} className="signal-row">
          <Icon name={signalIcon(item.family)} />
          <div>
            <strong>{item.title}</strong>
            <span>{item.detail}</span>
          </div>
          <StatusBadge value={item.severity || "info"} tone={severityTone(item.severity)} t={t} />
        </article>
      ))}
    </div>
  );
}

function ClusterTopologyPreview({ clusters, onOpenCluster, t }) {
  if (!clusters.length) return <EmptyState message={t("No clusters registered.")} />;
  return (
    <div className="mini-topology">
      {clusters.slice(0, 10).map((cluster) => (
        <button key={cluster.cluster_id} onClick={() => onOpenCluster(cluster)}>
          <Icon name="hdd-network" />
          <strong>{cluster.name}</strong>
          <StatusBadge value={cluster.status} tone={cluster.status === "active" ? "green" : "amber"} t={t} />
        </button>
      ))}
    </div>
  );
}

function RecentReport({ report, onOpenReport, t }) {
  if (!report) return <EmptyState message={t("No reports loaded.")} />;
  return (
    <article className="recent-report">
      <div className="report-cause">
        <span>{report.summary?.confidence || "unknown"}</span>
        <strong>{report.summary?.most_likely_cause || report.summary?.symptom}</strong>
      </div>
      <p>{(report.root_cause_candidates || [])[0]?.supporting_evidence?.[0] || "No evidence summary available."}</p>
      <button className="btn btn-sm btn-outline-secondary" onClick={() => onOpenReport(report.report_id)}>{t("Report detail")}</button>
    </article>
  );
}

function TimelineGraph({ timeline, report }) {
  const nodes = timeline?.nodes?.length ? timeline.nodes : fallbackTimeline(report);
  if (!nodes.length) return <EmptyState message="Timeline evidence is not available." />;
  return (
    <div className="timeline-graph">
      {nodes.slice(0, 10).map((node, index) => (
        <article key={node.id || `${node.title}-${index}`} className={node.root_trigger ? "root" : ""}>
          <div className="timeline-dot"><Icon name={node.root_trigger ? "bullseye" : signalIcon(node.component || node.signal_family)} /></div>
          <div>
            <time>{formatDate(node.timestamp || node.observed_at || report.created_at)}</time>
            <strong>{node.title || node.event_type || node.component}</strong>
            <span>{node.detail || node.signal_family || node.evidence_id}</span>
          </div>
          {index < nodes.length - 1 && <div className="timeline-edge" />}
        </article>
      ))}
    </div>
  );
}

function ActionDialog({ state, onClose, onConfirm, t }) {
  const [note, setNote] = useState("");
  const [busy, setBusy] = useState(false);
  const { report, action, index } = state;
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
          <span>{action.source === "llm" ? t("LLM diagnostic only") : POLICY_HELP[action.policy] || "Policy controlled workflow."}</span>
        </div>
        <div className="action-card blocked">
          <div className="action-head">
            <StatusBadge value={action.policy} tone={policyTone(action.policy)} t={t} />
            <StatusBadge value={action.automation_allowed ? "automation_allowed" : "automation_allowed=false"} tone={action.automation_allowed ? "green" : "amber"} />
          </div>
          <h3>{action.action}</h3>
          <p>{action.reason}</p>
          {action.execution_plan?.command_preview?.length > 0 && <pre className="command-preview">{action.execution_plan.command_preview.join("\n")}</pre>}
        </div>
        <textarea className="form-control" rows={3} placeholder="Operator note" value={note} onChange={(event) => setNote(event.target.value)} />
        <footer>
          <button className="btn btn-outline-secondary" onClick={onClose}>{t("Cancel")}</button>
          <button className="btn btn-primary" onClick={submit} disabled={busy}>{busy ? "..." : t("Confirm")}</button>
        </footer>
      </section>
    </div>
  );
}

function DeleteClusterDialog({ state, onClose, onConfirm, t }) {
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

function Surface({ title, subtitle, action, children }) {
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

function PageHeader({ title, subtitle, actions }) {
  return (
    <div className="page-header">
      <div>
        <p className="section-kicker">Console</p>
        <h1>{title}</h1>
        <p>{subtitle}</p>
      </div>
      {actions && <div className="page-actions">{actions}</div>}
    </div>
  );
}

function MetricTile({ label, value, tone = "blue", icon }) {
  return (
    <article className={`metric-tile ${tone}`}>
      <div className="metric-icon"><Icon name={icon} /></div>
      <span>{label}</span>
      <strong>{value}</strong>
    </article>
  );
}

function ResponsiveTable({ columns, rows, empty }) {
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

function StatusBadge({ value, tone, t = (x) => x }) {
  return <span className={`status-badge ${tone || statusTone(value)}`}>{t(String(value || "n/a"))}</span>;
}

function EmptyState({ message }) {
  return (
    <div className="empty-state">
      <Icon name="inbox" />
      <span>{message}</span>
    </div>
  );
}

function LanguageSwitch({ locale, setLocale, expanded = false }) {
  return (
    <div className={`language-switch ${expanded ? "expanded" : ""}`}>
      <button className={locale === "en" ? "active" : ""} onClick={() => setLocale("en")} type="button">EN</button>
      <button className={locale === "ko" ? "active" : ""} onClick={() => setLocale("ko")} type="button">KO</button>
    </div>
  );
}

function Toast({ tone, message }) {
  return <div className={`console-toast ${tone || "success"}`}>{message}</div>;
}

function Icon({ name }) {
  return <i className={`bi bi-${name}`} aria-hidden="true" />;
}

function arrayResult(result) {
  return result?.status === "fulfilled" && Array.isArray(result.value) ? result.value : [];
}

function sortByTime(items, field) {
  return [...(items || [])].sort((a, b) => new Date(b[field] || 0).getTime() - new Date(a[field] || 0).getTime());
}

async function copyText(text, notify) {
  await navigator.clipboard.writeText(text || "");
  notify("Copied.");
}

function buildAuditQuery(filters) {
  const query = new URLSearchParams();
  Object.entries(filters || {}).forEach(([key, value]) => {
    if (value !== undefined && value !== null && String(value).trim() !== "") {
      query.set(key, String(value).trim());
    }
  });
  if (!query.has("limit")) query.set("limit", "200");
  return query.toString();
}

function buildSignalDigest(reports, incidents) {
  const fromReports = (reports || []).flatMap((report) => {
    const candidates = report.root_cause_candidates || [];
    return candidates.slice(0, 2).map((candidate, index) => ({
      id: `${report.report_id}-${index}`,
      title: candidate.cause || report.summary?.symptom || "RCA signal",
      detail: (candidate.supporting_evidence || [])[0] || report.cluster_id,
      severity: candidate.confidence === "high" ? "critical" : "warning",
      family: inferSignalFamily(candidate.cause || report.summary?.most_likely_cause || ""),
    }));
  });
  const fromIncidents = (incidents || []).filter((incident) => incident.status === "open").map((incident) => ({
    id: incident.incident_id,
    title: incident.alert_name,
    detail: incident.root_cause || incident.cluster_id,
    severity: "critical",
    family: inferSignalFamily(`${incident.alert_name} ${incident.root_cause}`),
  }));
  return [...fromIncidents, ...fromReports];
}

function scoreStage(stage, reports, incidents) {
  const text = JSON.stringify([reports, incidents]).toLowerCase();
  const needles = {
    disk: ["disk", "inode", "io", "filesystem", "pressure"],
    runtime: ["containerd", "runtime", "docker", "crio"],
    kubelet: ["kubelet", "node not ready", "nodeready"],
    network: ["network", "cni", "conntrack", "dns", "mtu", "tcp"],
    control: ["apiserver", "api server", "etcd", "control plane"],
    service: ["service", "coredns", "endpoint", "latency"],
  }[stage] || [];
  return needles.reduce((count, needle) => count + occurrences(text, needle), 0);
}

function occurrences(text, needle) {
  if (!needle) return 0;
  return (text.match(new RegExp(escapeRegExp(needle), "g")) || []).length;
}

function escapeRegExp(value) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

function inferSignalFamily(value) {
  const text = String(value || "").toLowerCase();
  if (/disk|inode|io|filesystem|pressure/.test(text)) return "disk";
  if (/containerd|runtime|docker|crio/.test(text)) return "runtime";
  if (/kubelet|node/.test(text)) return "kubelet";
  if (/network|cni|conntrack|dns|mtu|tcp|nic/.test(text)) return "network";
  if (/api|etcd|control/.test(text)) return "control";
  return "service";
}

function evidenceSummary(report) {
  const rows = [];
  const trigger = report.trigger || {};
  Object.entries(trigger).forEach(([key, value]) => rows.push({ label: `trigger.${key}`, value: shortValue(value) }));
  (report.root_cause_candidates || []).forEach((candidate) => {
    (candidate.supporting_evidence || []).slice(0, 2).forEach((value) => rows.push({ label: candidate.cause, value }));
  });
  const evidence = report.evidence || report.evidence_summary || {};
  Object.entries(evidence).slice(0, 8).forEach(([key, value]) => rows.push({ label: key, value: shortValue(value) }));
  return rows;
}

function fallbackTimeline(report) {
  const candidates = report.root_cause_candidates || [];
  const root = candidates[0]?.cause || report.summary?.most_likely_cause;
  const items = [];
  if (root) {
    items.push({ id: "root", timestamp: report.created_at, component: inferSignalFamily(root), title: root, detail: "Most likely root cause", root_trigger: true });
  }
  (report.recommended_actions || []).slice(0, 4).forEach((action, index) => {
    items.push({ id: `action-${index}`, timestamp: report.created_at, component: action.action_key, title: action.action_key || action.policy, detail: action.reason });
  });
  return items;
}

function shortValue(value) {
  if (value === null || value === undefined) return "n/a";
  if (typeof value === "object") return JSON.stringify(value).slice(0, 180);
  return String(value).slice(0, 180);
}

function formatDate(value) {
  if (!value) return "n/a";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return String(value);
  return new Intl.DateTimeFormat(undefined, {
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  }).format(date);
}

function relativeTime(value) {
  if (!value) return "n/a";
  const diff = Date.now() - new Date(value).getTime();
  if (Number.isNaN(diff)) return String(value);
  const minutes = Math.round(diff / 60000);
  if (minutes < 1) return "now";
  if (minutes < 60) return `${minutes}m ago`;
  const hours = Math.round(minutes / 60);
  if (hours < 48) return `${hours}h ago`;
  return `${Math.round(hours / 24)}d ago`;
}

function statusTone(value) {
  if (["healthy", "active", "completed", "resolved", "success", "accepted"].includes(String(value))) return "green";
  if (["failed", "dead_letter", "offline", "blocked", "rejected", "critical"].includes(String(value))) return "red";
  if (["open", "degraded", "pending_approval", "queued", "processing", "retry_wait", "stale"].includes(String(value))) return "amber";
  return "muted";
}

function policyTone(policy) {
  if (policy === "AUTO_SAFE") return "green";
  if (policy === "APPROVAL_REQUIRED" || policy === "MANUAL_INVESTIGATION") return "amber";
  if (policy === "GITOPS_PR_ONLY") return "blue";
  if (policy === "NEVER_AUTO_EXECUTE") return "red";
  return "muted";
}

function confidenceTone(value) {
  if (value === "high") return "green";
  if (value === "medium") return "amber";
  if (value === "low") return "red";
  return "muted";
}

function severityTone(value) {
  if (["critical", "error", "high"].includes(String(value))) return "red";
  if (["warning", "medium"].includes(String(value))) return "amber";
  return "blue";
}

function requestTone(value) {
  if (["accepted", "completed", "approved_manual"].includes(String(value))) return "green";
  if (["blocked", "rejected", "failed"].includes(String(value))) return "red";
  return "amber";
}

function taskTone(value) {
  if (value === "completed") return "green";
  if (["failed", "dead_letter"].includes(String(value))) return "red";
  return "amber";
}

function auditTone(value) {
  if (String(value).includes("success")) return "green";
  if (String(value).includes("fail") || String(value).includes("denied")) return "red";
  return "amber";
}

function agentHealthTone(agent) {
  const value = agent.health_status || agent.status || agent.reported_status;
  if (value === "healthy") return "green";
  if (["offline", "unauthorized", "version_mismatch"].includes(value)) return "red";
  return "amber";
}

function signalIcon(value) {
  const family = inferSignalFamily(value);
  return {
    disk: "nvme",
    runtime: "boxes",
    kubelet: "cpu",
    network: "ethernet",
    control: "diagram-2",
    service: "activity",
  }[family] || "activity";
}

function auditClientIp(event) {
  return event.client_ip || event.details?.client_ip || event.details?.remote_addr || "-";
}

function auditSummary(details) {
  if (!details) return "-";
  if (typeof details === "string") return details;
  return Object.entries(details).slice(0, 4).map(([key, value]) => `${key}=${shortValue(value)}`).join(", ");
}

const root = createRoot(document.getElementById("rca-console-root"));
root.render(<ConsoleApp />);
