(function () {
  const rootElement = document.getElementById("rca-console-root");
  const h = React.createElement;
  const apiBase = rootElement.dataset.apiBase || "/console-api";
  const publicApiBase = rootElement.dataset.publicApiBase || window.location.origin;
  const views = [
    { id: "overview", label: "Overview", icon: "speedometer2" },
    { id: "clusters", label: "Clusters", icon: "hdd-network" },
    { id: "webhooks", label: "Webhooks", icon: "diagram-3" },
    { id: "reports", label: "Reports", icon: "clipboard2-pulse" },
    { id: "settings", label: "Settings", icon: "sliders" },
  ];

  function App() {
    const [activeView, setActiveView] = React.useState("overview");
    const [clusters, setClusters] = React.useState([]);
    const [reports, setReports] = React.useState([]);
    const [reportDetails, setReportDetails] = React.useState({});
    const [agentsByCluster, setAgentsByCluster] = React.useState({});
    const [installCommands, setInstallCommands] = React.useState({});
    const [sessionToken, setSessionToken] = React.useState(sessionStorage.getItem("rca_session_token") || "");
    const [currentUser, setCurrentUser] = React.useState(null);
    const [authChecking, setAuthChecking] = React.useState(Boolean(sessionStorage.getItem("rca_session_token")));
    const [toast, setToast] = React.useState("");
    const [loading, setLoading] = React.useState({});
    const [autoRefresh, setAutoRefresh] = React.useState(true);
    const [lastRefresh, setLastRefresh] = React.useState(null);
    const [clusterData, setClusterData] = React.useState(null);
    const [actionDialog, setActionDialog] = React.useState(null);
    const [collectionDialog, setCollectionDialog] = React.useState(null);

    const notify = React.useCallback((message) => {
      setToast(message);
      window.clearTimeout(notify.timer);
      notify.timer = window.setTimeout(() => setToast(""), 3200);
    }, []);

    const authHeaders = React.useCallback(() => {
      const headers = {};
      if (sessionToken) headers.Authorization = `Bearer ${sessionToken}`;
      return headers;
    }, [sessionToken]);

    const callApi = React.useCallback(async (path, options = {}) => {
      let response;
      try {
        response = await fetch(`${apiBase}${path}`, {
          cache: "no-store",
          credentials: "same-origin",
          ...options,
          headers: {
            "Content-Type": "application/json",
            ...(options.headers || {}),
          },
        });
      } catch (error) {
        throw new Error("Backend API is unreachable.");
      }

      const contentType = response.headers.get("content-type") || "";
      const text = await response.text();
      const body = contentType.includes("application/json") && text ? JSON.parse(text) : text;
      if (!response.ok) {
        throw new Error(readError(body, response.statusText));
      }
      return body;
    }, []);

    const loadCurrentUser = React.useCallback(async (silent) => {
      if (!sessionToken) {
        setCurrentUser(null);
        setAuthChecking(false);
        return;
      }
      try {
        const user = await callApi("/api/auth/me", { headers: authHeaders() });
        setCurrentUser(user);
      } catch (error) {
        setSessionToken("");
        setCurrentUser(null);
        sessionStorage.removeItem("rca_session_token");
        if (!silent) notify(error.message);
      } finally {
        setAuthChecking(false);
      }
    }, [sessionToken, authHeaders, callApi, notify]);

    const loadClusters = React.useCallback(async (silent) => {
      try {
        setLoading((value) => ({ ...value, clusters: true }));
        const result = await callApi("/api/clusters", { headers: authHeaders() });
        setClusters(Array.isArray(result) ? result : []);
      } catch (error) {
        setClusters([]);
        if (!silent) notify(error.message);
      } finally {
        setLoading((value) => ({ ...value, clusters: false }));
      }
    }, [authHeaders, callApi, notify]);

    const loadReports = React.useCallback(async (silent) => {
      try {
        setLoading((value) => ({ ...value, reports: true }));
        const result = await callApi("/api/rca/reports", { headers: authHeaders() });
        setReports(Array.isArray(result) ? result : []);
      } catch (error) {
        setReports([]);
        if (!silent) notify(error.message);
      } finally {
        setLoading((value) => ({ ...value, reports: false }));
      }
    }, [authHeaders, callApi, notify]);

    const refreshAll = React.useCallback(async (silent) => {
      await Promise.allSettled([
        loadClusters(silent),
        loadReports(silent),
      ]);
      setLastRefresh(new Date());
    }, [loadClusters, loadReports]);

    React.useEffect(() => {
      loadCurrentUser(true);
    }, [loadCurrentUser]);

    React.useEffect(() => {
      if (currentUser) refreshAll(true);
    }, [currentUser, refreshAll]);

    React.useEffect(() => {
      if (!autoRefresh || !currentUser) return undefined;
      const timer = window.setInterval(() => refreshAll(true), 30000);
      return () => window.clearInterval(timer);
    }, [autoRefresh, currentUser, refreshAll]);

    async function login(event) {
      event.preventDefault();
      const form = event.currentTarget;
      const payload = formPayload(form);
      try {
        const session = await callApi("/api/auth/login", {
          method: "POST",
          body: JSON.stringify(payload),
        });
        setSessionToken(session.access_token);
        setCurrentUser(session.user);
        sessionStorage.setItem("rca_session_token", session.access_token);
        form.reset();
        notify(`Signed in: ${session.user.email}`);
      } catch (error) {
        notify(error.message);
      }
    }

    async function logout() {
      if (sessionToken) {
        await callApi("/api/auth/logout", {
          method: "POST",
          headers: authHeaders(),
        }).catch(() => null);
      }
      setSessionToken("");
      setCurrentUser(null);
      setClusters([]);
      setReports([]);
      setReportDetails({});
      setAgentsByCluster({});
      setClusterData(null);
      setActionDialog(null);
      setCollectionDialog(null);
      sessionStorage.removeItem("rca_session_token");
      notify("Signed out.");
    }

    async function changePassword(event) {
      event.preventDefault();
      const form = event.currentTarget;
      const payload = formPayload(form);
      if (payload.new_password !== payload.confirm_password) {
        notify("New passwords do not match.");
        return;
      }
      delete payload.confirm_password;
      try {
        await callApi("/api/auth/change-password", {
          method: "POST",
          headers: authHeaders(),
          body: JSON.stringify(payload),
        });
        form.reset();
        notify("Password changed.");
      } catch (error) {
        notify(error.message);
      }
    }

    async function createCluster(event) {
      event.preventDefault();
      const form = event.currentTarget;
      try {
        const payload = formPayload(form);
        const backendUrl = payload.backend_url;
        delete payload.backend_url;
        const cluster = await callApi("/api/clusters", {
          method: "POST",
          headers: authHeaders(),
          body: JSON.stringify(payload),
        });
        form.reset();
        notify(`Cluster registered: ${cluster.name}. Install command is ready.`);
        await loadClusters(false);
        await loadInstallCommand(cluster.cluster_id, backendUrl);
        await loadAgents(cluster.cluster_id);
      } catch (error) {
        notify(error.message);
      }
    }

    async function loadInstallCommand(clusterId, backendUrl) {
      try {
        setInstallCommands((value) => ({ ...value, [clusterId]: "Loading..." }));
        const query = new URLSearchParams();
        if (backendUrl || publicApiBase) query.set("backend_url", backendUrl || publicApiBase);
        const suffix = query.toString() ? `?${query}` : "";
        const response = await callApi(`/api/clusters/${encodeURIComponent(clusterId)}/install-command${suffix}`, {
          headers: authHeaders(),
        });
        setInstallCommands((value) => ({ ...value, [clusterId]: response.commands.join("\n") }));
      } catch (error) {
        setInstallCommands((value) => ({ ...value, [clusterId]: error.message }));
      }
    }

    async function loadAgents(clusterId) {
      try {
        setAgentsByCluster((value) => ({ ...value, [clusterId]: { loading: true, items: [] } }));
        const agents = await callApi(`/api/clusters/${encodeURIComponent(clusterId)}/agents`, {
          headers: authHeaders(),
        });
        setAgentsByCluster((value) => ({ ...value, [clusterId]: { loading: false, items: agents } }));
      } catch (error) {
        setAgentsByCluster((value) => ({ ...value, [clusterId]: { loading: false, error: error.message, items: [] } }));
      }
    }

    async function loadClusterData(clusterId) {
      setClusterData({ open: true, loading: true, clusterId });
      try {
        const [cluster, agents, evidenceRequests, allReports] = await Promise.all([
          callApi(`/api/clusters/${encodeURIComponent(clusterId)}`, { headers: authHeaders() }),
          callApi(`/api/clusters/${encodeURIComponent(clusterId)}/agents`, { headers: authHeaders() }),
          callApi(`/api/clusters/${encodeURIComponent(clusterId)}/evidence-requests`, { headers: authHeaders() }),
          callApi("/api/rca/reports", { headers: authHeaders() }),
        ]);
        setClusterData({
          open: true,
          loading: false,
          clusterId,
          cluster,
          agents: Array.isArray(agents) ? agents : [],
          evidenceRequests: Array.isArray(evidenceRequests) ? evidenceRequests : [],
          reports: (Array.isArray(allReports) ? allReports : []).filter((report) => report.cluster_id === clusterId),
        });
      } catch (error) {
        setClusterData({ open: true, loading: false, clusterId, error: error.message });
      }
    }

    async function loadEvidenceBundle(evidenceId) {
      if (!evidenceId) return;
      setClusterData((value) => ({ ...(value || {}), evidenceLoading: true, selectedEvidence: null, evidenceError: null }));
      try {
        const evidence = await callApi(`/api/evidence/${encodeURIComponent(evidenceId)}`, {
          headers: authHeaders(),
        });
        setClusterData((value) => ({ ...(value || {}), evidenceLoading: false, selectedEvidence: evidence, evidenceError: null }));
      } catch (error) {
        setClusterData((value) => ({ ...(value || {}), evidenceLoading: false, evidenceError: error.message }));
      }
    }

    async function toggleReport(reportId) {
      if (reportDetails[reportId]?.open) {
        setReportDetails((value) => ({ ...value, [reportId]: { ...value[reportId], open: false } }));
        return;
      }
      setReportDetails((value) => ({ ...value, [reportId]: { ...(value[reportId] || {}), open: true, loading: true } }));
      try {
        const report = await callApi(`/api/rca/reports/${encodeURIComponent(reportId)}`, {
          headers: authHeaders(),
        });
        setReportDetails((value) => ({ ...value, [reportId]: { open: true, loading: false, report } }));
      } catch (error) {
        setReportDetails((value) => ({ ...value, [reportId]: { open: true, loading: false, error: error.message } }));
      }
    }

    function prepareRecommendedAction(report, action, actionIndex) {
      setActionDialog({ report, action, actionIndex, loading: false });
    }

    function prepareClusterCollection(cluster) {
      setCollectionDialog({ cluster, loading: false });
    }

    async function executeClusterCollection() {
      if (!collectionDialog?.cluster) return;
      const cluster = collectionDialog.cluster;
      setCollectionDialog((value) => ({ ...value, loading: true }));
      try {
        const result = await callApi(`/api/clusters/${encodeURIComponent(cluster.cluster_id)}/collection-runs`, {
          method: "POST",
          headers: authHeaders(),
          body: JSON.stringify({
            confirmed: true,
            reason: "Manual backend collection from web console",
            context: { source: "web-console" },
          }),
        });
        setCollectionDialog(null);
        notify(`Collection requested: ${result.created_evidence_requests.length} nodes, skipped ${result.skipped_nodes.length}.`);
        await loadAgents(cluster.cluster_id);
        if (clusterData?.clusterId === cluster.cluster_id) {
          await loadClusterData(cluster.cluster_id);
        }
      } catch (error) {
        setCollectionDialog((value) => ({ ...value, loading: false, error: error.message }));
      }
    }

    async function executeRecommendedAction() {
      if (!actionDialog) return;
      const { report, actionIndex } = actionDialog;
      setActionDialog((value) => ({ ...value, loading: true }));
      try {
        const result = await callApi(
          `/api/rca/reports/${encodeURIComponent(report.report_id)}/actions/${actionIndex}/execute`,
          {
            method: "POST",
            headers: authHeaders(),
            body: JSON.stringify({ confirmed: true }),
          }
        );
        setActionDialog(null);
        notify(result.evidence_request ? `${result.message} ${result.evidence_request.request_id}` : result.message);
        if (result.evidence_request) loadClusterData(report.cluster_id);
      } catch (error) {
        setActionDialog((value) => ({ ...value, loading: false, error: error.message }));
      }
    }

    async function copyText(value, message) {
      try {
        await navigator.clipboard.writeText(value);
        notify(message);
      } catch (error) {
        notify(value);
      }
    }

    const webhookEndpoint = `${publicApiBase.replace(/\/$/, "")}/api/webhooks/alertmanager`;

    if (authChecking) {
      return h("div", { className: "login-shell" },
        h("div", { className: "login-card" },
          h("div", { className: "console-brand-mark mb-3" }, "RCA"),
          h("h1", { className: "h5 mb-2" }, "Checking session"),
          h("p", { className: "text-muted mb-0" }, "잠시만 기다려주세요.")
        )
      );
    }

    if (!currentUser) {
      return h(React.Fragment, null,
        h(LoginPage, { onLogin: login }),
        toast && h(Toast, { message: toast, onClose: () => setToast("") })
      );
    }

    return h("div", { className: "console-shell" },
      h(Sidebar, { activeView, setActiveView }),
      h("main", { className: "console-main" },
        h(Topbar, {
          currentUser,
          autoRefresh,
          lastRefresh,
          onLogout: logout,
          onRefresh: () => refreshAll(false),
          onToggleAutoRefresh: () => setAutoRefresh((value) => !value),
        }),
        activeView === "overview" && h(OverviewView, {
          clusters,
          reports,
          loading,
          webhookEndpoint,
          onNavigate: setActiveView,
        }),
        activeView === "clusters" && h(ClustersView, {
          clusters,
          loading,
          agentsByCluster,
          installCommands,
          onCreateCluster: createCluster,
          onLoadClusters: () => loadClusters(false),
          onLoadInstallCommand: loadInstallCommand,
          onLoadAgents: loadAgents,
          onOpenClusterData: loadClusterData,
          onCollectCluster: prepareClusterCollection,
          onCopy: copyText,
          publicApiBase,
        }),
        activeView === "webhooks" && h(WebhooksView, {
          endpoint: webhookEndpoint,
          onCopy: copyText,
        }),
        activeView === "reports" && h(ReportsView, {
          reports,
          loading,
          reportDetails,
          onLoadReports: () => loadReports(false),
          onToggleReport: toggleReport,
          onPrepareAction: prepareRecommendedAction,
          onCopy: copyText,
        }),
        activeView === "settings" && h(SettingsView, {
          apiBase,
          publicApiBase,
          autoRefresh,
          currentUser,
          onChangePassword: changePassword,
        }),
        clusterData?.open && h(ClusterDataModal, {
          state: clusterData,
          onClose: () => setClusterData(null),
          onRefresh: () => loadClusterData(clusterData.clusterId),
          onLoadEvidence: loadEvidenceBundle,
          onCollectCluster: prepareClusterCollection,
          onCopy: copyText,
        }),
        actionDialog && h(ActionConfirmDialog, {
          state: actionDialog,
          onCancel: () => setActionDialog(null),
          onConfirm: executeRecommendedAction,
        }),
        collectionDialog && h(CollectionConfirmDialog, {
          state: collectionDialog,
          onCancel: () => setCollectionDialog(null),
          onConfirm: executeClusterCollection,
        }),
        toast && h(Toast, { message: toast, onClose: () => setToast("") })
      )
    );
  }

  function Sidebar({ activeView, setActiveView }) {
    return h("aside", { className: "console-sidebar" },
      h("div", { className: "console-brand" },
        h("div", { className: "console-brand-mark" }, "RCA"),
        h("div", null,
          h("div", { className: "fw-bold" }, "Infra RCA"),
          h("div", { className: "small text-white-50" }, "Operations Console")
        )
      ),
      h("nav", { className: "console-nav", "aria-label": "Console navigation" },
        views.map((view) => h("button", {
          key: view.id,
          type: "button",
          className: activeView === view.id ? "active" : "",
          onClick: () => setActiveView(view.id),
        }, h(Icon, { name: view.icon }), h("span", null, view.label)))
      )
    );
  }

  function Topbar(props) {
    return h("section", { className: "console-topbar" },
      h("div", { className: "row g-3 align-items-end" },
        h("div", { className: "col-12 col-xl-4" },
          h("div", { className: "d-flex align-items-center gap-2 mb-1" },
            h("span", { className: "status-dot online" }),
            h("span", { className: "small text-muted fw-semibold" }, `${props.currentUser.email} / ${props.currentUser.role}`)
          ),
          h("h1", { className: "h4 mb-0" }, "Cluster Infrastructure RCA")
        ),
        h("div", { className: "col-12 col-xl-8" },
          h("div", { className: "d-flex gap-2 flex-wrap justify-content-xl-end" },
            h("button", { type: "button", className: "btn btn-outline-secondary btn-icon", onClick: props.onRefresh }, h(Icon, { name: "arrow-clockwise" }), "Refresh"),
            h("button", { type: "button", className: `btn btn-outline-secondary btn-icon ${props.autoRefresh ? "active" : ""}`, onClick: props.onToggleAutoRefresh }, h(Icon, { name: "activity" }), props.autoRefresh ? "Auto" : "Manual"),
            h("button", { type: "button", className: "btn btn-outline-secondary btn-icon", onClick: props.onLogout }, h(Icon, { name: "box-arrow-right" }), "Logout")
          ),
          h("div", { className: "small text-muted mt-1" }, props.lastRefresh ? `Last refresh ${formatDate(props.lastRefresh)}` : "Not refreshed")
        )
      )
    );
  }

  function LoginPage({ onLogin }) {
    return h("div", { className: "login-shell" },
      h("section", { className: "login-card" },
        h("div", { className: "console-brand-mark mb-3" }, "RCA"),
        h("h1", { className: "h4 mb-2" }, "Cluster Infrastructure RCA"),
        h("p", { className: "text-muted mb-4" }, "관리자 계정으로 로그인하세요. 초기 계정은 admin / admin 입니다."),
        h("form", { className: "d-grid gap-3", onSubmit: onLogin },
          h("div", null,
            h("label", { className: "form-label", htmlFor: "login-username" }, "Account"),
            h("input", { id: "login-username", className: "form-control", name: "username", autoComplete: "username", defaultValue: "admin", required: true })
          ),
          h("div", null,
            h("label", { className: "form-label", htmlFor: "login-password" }, "Password"),
            h("input", { id: "login-password", className: "form-control", name: "password", type: "password", autoComplete: "current-password", required: true })
          ),
          h("button", { className: "btn btn-primary btn-icon justify-content-center", type: "submit" }, h(Icon, { name: "box-arrow-in-right" }), "Login")
        )
      )
    );
  }

  function OverviewView({ clusters, reports, loading, webhookEndpoint, onNavigate }) {
    const highConfidence = reports.filter((report) => report.summary?.confidence === "high").length;
    return h("div", { className: "d-grid gap-3" },
      h("div", { className: "row g-3" },
        h(MetricTile, { label: "Clusters", value: clusters.length, hint: loading.clusters ? "Loading" : "Registered targets", icon: "hdd-network" }),
        h(MetricTile, { label: "RCA Reports", value: reports.length, hint: `${highConfidence} high confidence`, icon: "clipboard2-pulse" }),
        h(MetricTile, { label: "Access", value: "Session", hint: "Bearer protected", icon: "shield-lock" }),
        h(MetricTile, { label: "Webhook", value: "Alertmanager", hint: webhookEndpoint, icon: "diagram-3", compact: true })
      ),
      h("div", { className: "row g-3" },
        h("div", { className: "col-12 col-xl-7" },
          h(Panel, { title: "Cluster Snapshot", subtitle: "Latest registered clusters", action: h("button", { className: "btn btn-sm btn-outline-secondary", onClick: () => onNavigate("clusters") }, "Open") },
            h(ClusterTable, { clusters: clusters.slice(0, 6) })
          )
        ),
        h("div", { className: "col-12 col-xl-5" },
          h(Panel, { title: "Recent Reports", subtitle: "Root cause candidates", action: h("button", { className: "btn btn-sm btn-outline-secondary", onClick: () => onNavigate("reports") }, "Open") },
            reports.length ? h("div", { className: "list-group list-group-flush" },
              reports.slice(0, 5).map((report) => h("div", { key: report.report_id, className: "list-group-item px-0" },
                h("div", { className: "d-flex justify-content-between gap-2" },
                  h("strong", { className: "small" }, report.summary?.symptom || "Unknown symptom"),
                  h(StatusBadge, { value: report.summary?.confidence || "unknown", tone: confidenceTone(report.summary?.confidence) })
                ),
                h("div", { className: "small text-muted text-truncate" }, report.summary?.most_likely_cause || report.report_id)
              ))
            ) : h(EmptyState, { message: "No reports loaded." })
          )
        )
      )
    );
  }

  function ClustersView(props) {
    const backendUrlHelp = props.publicApiBase
      ? "Agents and kubectl will use this backend API URL."
      : "Enter the backend API URL reachable from your kubectl workstation and cluster nodes.";
    return h("div", { className: "d-grid gap-3" },
      h("div", { className: "row g-3" },
        h("div", { className: "col-12 col-xl-5" },
          h(Panel, { title: "Cluster Onboarding", subtitle: "Register once, then install the node agent" },
            h("ol", { className: "onboarding-steps mb-3" },
              h("li", null, h("span", null, "1"), h("div", null, h("strong", null, "Register"), h("small", null, "Create a cluster id and bootstrap token."))),
              h("li", null, h("span", null, "2"), h("div", null, h("strong", null, "Install"), h("small", null, "Run the generated kubectl command."))),
              h("li", null, h("span", null, "3"), h("div", null, h("strong", null, "Verify"), h("small", null, "Check node agents after DaemonSet rollout.")))
            ),
            h("form", { className: "row g-3", onSubmit: props.onCreateCluster },
              h(InputField, { label: "Cluster name", name: "name", required: true, placeholder: "prod-cluster" }),
              h("div", { className: "col-12" },
                h("label", { className: "form-label", htmlFor: "cluster-environment" }, "Environment"),
                h("select", { id: "cluster-environment", className: "form-select", name: "environment", defaultValue: "prod" },
                  h("option", { value: "prod" }, "prod"),
                  h("option", { value: "stage" }, "stage"),
                  h("option", { value: "dev" }, "dev")
                )
              ),
              h("div", { className: "col-12" },
                h("label", { className: "form-label", htmlFor: "cluster-backend-url" }, "Backend API URL for agents"),
                h("input", {
                  id: "cluster-backend-url",
                  className: "form-control font-monospace",
                  name: "backend_url",
                  type: "url",
                  defaultValue: props.publicApiBase || "",
                  placeholder: "https://rca-api.example.com",
                  required: true,
                }),
                h("div", { className: "form-text" }, backendUrlHelp)
              ),
              h("div", { className: "col-12" },
                h("label", { className: "form-label", htmlFor: "cluster-description" }, "Description"),
                h("textarea", { id: "cluster-description", className: "form-control", name: "description", rows: 2, placeholder: "Optional note for operators" })
              ),
              h("div", { className: "col-12 d-grid" },
                h("button", { className: "btn btn-primary btn-icon justify-content-center", type: "submit" }, h(Icon, { name: "plus-lg" }), "Register and show install command")
              )
            )
          )
        ),
        h("div", { className: "col-12 col-xl-7" },
          h(Panel, {
            title: "Registered Clusters",
            subtitle: props.loading.clusters ? "Loading" : `${props.clusters.length} clusters`,
            action: h("button", { className: "btn btn-sm btn-outline-secondary btn-icon", onClick: props.onLoadClusters }, h(Icon, { name: "arrow-clockwise" }), "Reload"),
          }, props.clusters.length ? h("div", { className: "table-responsive" },
            h("table", { className: "table table-hover mb-0" },
              h("thead", null, h("tr", null,
                h("th", null, "Cluster"),
                h("th", null, "Environment"),
                h("th", null, "Status"),
                h("th", { className: "text-end" }, "Actions")
              )),
              h("tbody", null, props.clusters.map((cluster) => h(React.Fragment, { key: cluster.cluster_id },
                h("tr", null,
                  h("td", null,
                    h("button", {
                      type: "button",
                      className: "cluster-name-button",
                      onClick: () => props.onOpenClusterData(cluster.cluster_id),
                    }, cluster.name),
                    h("div", { className: "small text-muted font-monospace" }, cluster.cluster_id)
                  ),
                  h("td", null, cluster.environment),
                  h("td", null, h(StatusBadge, { value: cluster.status, tone: clusterStatusTone(cluster.status) })),
                  h("td", { className: "text-end" },
                    h("div", { className: "btn-group btn-group-sm" },
                      h("button", { type: "button", className: "btn btn-outline-secondary btn-icon", onClick: () => props.onOpenClusterData(cluster.cluster_id) }, h(Icon, { name: "window-sidebar" }), "Data"),
                      h("button", { type: "button", className: "btn btn-outline-secondary btn-icon", onClick: () => props.onCollectCluster(cluster) }, h(Icon, { name: "radar" }), "Collect"),
                      h("button", { className: "btn btn-outline-secondary btn-icon", onClick: () => props.onLoadInstallCommand(cluster.cluster_id) }, h(Icon, { name: "terminal" }), "Install"),
                      h("button", { className: "btn btn-outline-secondary btn-icon", onClick: () => props.onLoadAgents(cluster.cluster_id) }, h(Icon, { name: "hdd-stack" }), "Agents")
                    )
                  )
                ),
                (props.installCommands[cluster.cluster_id] || props.agentsByCluster[cluster.cluster_id]) && h("tr", null,
                  h("td", { colSpan: 4 },
                    props.installCommands[cluster.cluster_id] && h(InstallCommandPanel, {
                      command: props.installCommands[cluster.cluster_id],
                      onCopy: props.onCopy,
                    }),
                    props.agentsByCluster[cluster.cluster_id] && h(AgentsTable, { state: props.agentsByCluster[cluster.cluster_id] })
                  )
                )
              )))
            )
          ) : h(EmptyState, { message: "No registered clusters loaded." }))
        )
      )
    );
  }

  function InstallCommandPanel({ command, onCopy }) {
    const isLoading = command === "Loading...";
    const canCopy = command && !isLoading && !command.toLowerCase().includes("failed") && !command.toLowerCase().includes("invalid");
    return h("div", { className: "install-command-panel mb-3" },
      h("div", { className: "install-command-header" },
        h("div", null,
          h("div", { className: "fw-semibold" }, "Agent install command"),
          h("div", { className: "small text-muted" }, "Run this from a workstation with kubectl access to the target cluster.")
        ),
        h("button", {
          type: "button",
          className: "btn btn-sm btn-outline-secondary btn-icon",
          disabled: !canCopy,
          onClick: () => onCopy(command, "Install command copied."),
        }, h(Icon, { name: "clipboard" }), "Copy")
      ),
      h("pre", { className: "code-block" }, command),
      h("div", { className: "install-checklist" },
        h("span", null, h(Icon, { name: "1-circle" }), "Namespace and secret are created first."),
        h("span", null, h(Icon, { name: "2-circle" }), "DaemonSet is applied from the generated manifest URL."),
        h("span", null, h(Icon, { name: "3-circle" }), "Click Agents after rollout to confirm node registration.")
      )
    );
  }

  function WebhooksView({ endpoint, onCopy }) {
    const sample = `receivers:\n  - name: cluster-infra-rca\n    webhook_configs:\n      - url: ${endpoint}\n        send_resolved: true\n        http_config:\n          authorization:\n            type: Bearer\n            credentials_file: /etc/alertmanager/secrets/rca-webhook-token`;
    return h("div", { className: "row g-3" },
      h("div", { className: "col-12 col-xl-5" },
        h(Panel, { title: "Webhook Endpoint", subtitle: "Alertmanager integration" },
          h("div", { className: "d-grid gap-3" },
            h("div", null,
              h("label", { className: "form-label" }, "Endpoint"),
              h("div", { className: "input-group" },
                h("input", { className: "form-control font-monospace", readOnly: true, value: endpoint }),
                h("button", { className: "btn btn-outline-secondary", onClick: () => onCopy(endpoint, "Webhook endpoint copied.") }, h(Icon, { name: "clipboard" }))
              )
            ),
            h("div", null,
              h("label", { className: "form-label" }, "Authorization"),
              h("code", { className: "d-block p-3 bg-light rounded-2" }, "Authorization: Bearer ${RCA_WEBHOOK_TOKEN}")
            )
          )
        )
      ),
      h("div", { className: "col-12 col-xl-7" },
        h(Panel, { title: "Alertmanager Receiver", subtitle: "YAML sample", action: h("button", { className: "btn btn-sm btn-outline-secondary btn-icon", onClick: () => onCopy(sample, "Receiver sample copied.") }, h(Icon, { name: "clipboard" }), "Copy") },
          h("pre", { className: "code-block" }, sample)
        )
      )
    );
  }

  function ReportsView({ reports, loading, reportDetails, onLoadReports, onToggleReport, onPrepareAction, onCopy }) {
    return h(Panel, {
      title: "RCA Reports",
      subtitle: loading.reports ? "Loading" : `${reports.length} reports`,
      action: h("button", { className: "btn btn-sm btn-outline-secondary btn-icon", onClick: onLoadReports }, h(Icon, { name: "arrow-clockwise" }), "Reload"),
    }, reports.length ? h("div", { className: "table-responsive" },
      h("table", { className: "table table-hover mb-0" },
        h("thead", null, h("tr", null,
          h("th", null, "Symptom"),
          h("th", null, "Cluster"),
          h("th", null, "Confidence"),
          h("th", null, "Policy"),
          h("th", { className: "text-end" }, "Actions")
        )),
        h("tbody", null, reports.map((report) => {
          const detail = reportDetails[report.report_id];
          return h(React.Fragment, { key: report.report_id },
            h("tr", null,
              h("td", null,
                h("div", { className: "fw-semibold" }, report.summary?.symptom || "Unknown symptom"),
                h("div", { className: "small text-muted text-truncate-cell" }, report.summary?.most_likely_cause || report.report_id)
              ),
              h("td", { className: "font-monospace small" }, report.cluster_id),
              h("td", null, h(StatusBadge, { value: report.summary?.confidence || "unknown", tone: confidenceTone(report.summary?.confidence) })),
              h("td", null, uniquePolicies(report).map((policy) => h(StatusBadge, { key: policy, value: policy, tone: policyTone(policy) }))),
              h("td", { className: "text-end" },
                h("div", { className: "btn-group btn-group-sm" },
                  h("button", { className: "btn btn-outline-secondary", onClick: () => onToggleReport(report.report_id) }, detail?.open ? "Hide" : "Detail"),
                  h("button", { className: "btn btn-outline-secondary", onClick: () => onCopy(JSON.stringify(report, null, 2), "Report summary copied.") }, "Copy")
                )
              )
            ),
            detail?.open && h("tr", null, h("td", { colSpan: 5 }, h(ReportDetail, { detail, onPrepareAction })))
          );
        }))
      )
    ) : h(EmptyState, { message: "No reports loaded." }));
  }

  function SettingsView({ apiBase, publicApiBase, autoRefresh, currentUser, onChangePassword }) {
    const rows = [
      ["Console proxy", apiBase],
      ["Public API", publicApiBase],
      ["Signed in", currentUser.email],
      ["Role", currentUser.role],
      ["Refresh mode", autoRefresh ? "auto / 30s" : "manual"],
      ["Webhook token env", "RCA_WEBHOOK_TOKEN"],
      ["LLM provider env", "RCA_LLM_PROVIDER"],
      ["Database env", "RCA_DATABASE_URL"],
    ];
    return h("div", { className: "row g-3" },
      h("div", { className: "col-12 col-xl-8" },
        h(Panel, { title: "Console Settings", subtitle: "Runtime references" },
          h("div", { className: "row g-3" },
            rows.map(([label, value]) => h("div", { className: "col-12 col-md-6 col-xl-3", key: label },
              h("div", { className: "border rounded-2 p-3 bg-light h-100" },
                h("div", { className: "small text-muted fw-semibold mb-2" }, label),
                h("code", { className: "small text-break" }, value)
              )
            ))
          )
        )
      ),
      h("div", { className: "col-12 col-xl-4" },
        h(Panel, { title: "Change Password", subtitle: "현재 로그인 계정의 비밀번호 변경" },
          h("form", { className: "row g-3", onSubmit: onChangePassword },
            h(InputField, { label: "Current password", name: "current_password", type: "password", required: true, autoComplete: "current-password" }),
            h(InputField, { label: "New password", name: "new_password", type: "password", minLength: 8, required: true, autoComplete: "new-password" }),
            h(InputField, { label: "Confirm password", name: "confirm_password", type: "password", minLength: 8, required: true, autoComplete: "new-password" }),
            h("div", { className: "col-12 d-grid" },
              h("button", { className: "btn btn-primary btn-icon justify-content-center", type: "submit" }, h(Icon, { name: "key" }), "Update password")
            )
          )
        )
      )
    );
  }

  function MetricTile({ label, value, hint, icon, compact }) {
    return h("div", { className: "col-12 col-md-6 col-xl-3" },
      h("div", { className: "metric-tile" },
        h("div", { className: "d-flex justify-content-between gap-2" },
          h("span", { className: "label" }, label),
          h(Icon, { name: icon })
        ),
        h("span", { className: compact ? "value h5" : "value" }, value),
        h("div", { className: "hint text-truncate" }, hint)
      )
    );
  }

  function Panel({ title, subtitle, action, children }) {
    return h("section", { className: "console-panel" },
      h("div", { className: "console-panel-header" },
        h("div", null, h("h2", { className: "console-panel-title" }, title), subtitle && h("p", { className: "console-panel-subtitle" }, subtitle)),
        action && h("div", null, action)
      ),
      h("div", { className: "console-panel-body" }, children)
    );
  }

  function ClusterTable({ clusters }) {
    if (!clusters.length) return h(EmptyState, { message: "No clusters loaded." });
    return h("div", { className: "table-responsive" },
      h("table", { className: "table table-hover mb-0" },
        h("thead", null, h("tr", null, h("th", null, "Name"), h("th", null, "Environment"), h("th", null, "Status"))),
        h("tbody", null, clusters.map((cluster) => h("tr", { key: cluster.cluster_id },
          h("td", null, h("div", { className: "fw-semibold" }, cluster.name), h("div", { className: "small text-muted font-monospace" }, cluster.cluster_id)),
          h("td", null, cluster.environment),
          h("td", null, h(StatusBadge, { value: cluster.status, tone: clusterStatusTone(cluster.status) }))
        )))
      )
    );
  }

  function AgentsTable({ state }) {
    if (state.loading) return h(EmptyState, { message: "Loading agents." });
    if (state.error) return h(EmptyState, { message: state.error });
    if (!state.items.length) return h(EmptyState, { message: "No agents registered." });
    return h("div", { className: "table-responsive" },
      h("table", { className: "table table-sm mb-0" },
        h("thead", null, h("tr", null, h("th", null, "Node"), h("th", null, "Status"), h("th", null, "Version"), h("th", null, "Last seen"))),
        h("tbody", null, state.items.map((agent) => h("tr", { key: agent.node_name },
          h("td", { className: "font-monospace small" }, agent.node_name),
          h("td", null, h(StatusBadge, { value: agent.status || "unknown", tone: agentStatusTone(agent.status) })),
          h("td", null, agent.agent_version || "n/a"),
          h("td", null, formatAgentLastSeen(agent))
        )))
      )
    );
  }

  function ClusterDataModal({ state, onClose, onRefresh, onLoadEvidence, onCollectCluster, onCopy }) {
    const cluster = state.cluster || {};
    const agents = state.agents || [];
    const evidenceRequests = state.evidenceRequests || [];
    const reports = state.reports || [];
    const body = state.loading
      ? h(EmptyState, { message: "Loading cluster data." })
      : state.error
        ? h(EmptyState, { message: state.error })
        : h("div", { className: "d-grid gap-3" },
          h("div", { className: "summary-grid" },
            h(SummaryBox, { label: "Status", value: h(StatusBadge, { value: cluster.status, tone: clusterStatusTone(cluster.status) }) }),
            h(SummaryBox, { label: "Environment", value: cluster.environment || "n/a" }),
            h(SummaryBox, { label: "Agents", value: agents.length }),
            h(SummaryBox, { label: "Evidence requests", value: evidenceRequests.length })
          ),
          h("div", null,
            h("h3", { className: "h6 mb-2" }, "Node Agents"),
            h(AgentsTable, { state: { loading: false, items: agents } })
          ),
          h("div", null,
            h("h3", { className: "h6 mb-2" }, "Evidence Requests"),
            h(EvidenceRequestTable, { items: evidenceRequests, onLoadEvidence })
          ),
          h("div", null,
            h("div", { className: "d-flex justify-content-between gap-2 align-items-center mb-2" },
              h("h3", { className: "h6 mb-0" }, "Collected Evidence"),
              state.selectedEvidence && h("button", {
                type: "button",
                className: "btn btn-sm btn-outline-secondary btn-icon",
                onClick: () => onCopy(JSON.stringify(state.selectedEvidence, null, 2), "Evidence bundle copied."),
              }, h(Icon, { name: "clipboard" }), "Copy")
            ),
            h(EvidenceBundlePreview, { state })
          ),
          h("div", null,
            h("div", { className: "d-flex justify-content-between gap-2 align-items-center mb-2" },
              h("h3", { className: "h6 mb-0" }, "Recent RCA"),
              reports.length ? h("button", {
                type: "button",
                className: "btn btn-sm btn-outline-secondary btn-icon",
                onClick: () => onCopy(JSON.stringify(reports, null, 2), "Cluster RCA reports copied."),
              }, h(Icon, { name: "clipboard" }), "Copy") : null
            ),
            h(ClusterReportList, { items: reports })
          )
        );

    return h("div", { className: "console-modal-backdrop", role: "presentation", onMouseDown: (event) => event.target === event.currentTarget && onClose() },
      h("section", { className: "console-modal cluster-data-modal", role: "dialog", "aria-modal": "true" },
        h("div", { className: "console-modal-header" },
          h("div", null,
            h("h2", { className: "h5 mb-1" }, cluster.name || state.clusterId),
          h("div", { className: "small text-muted font-monospace" }, state.clusterId)
          ),
          h("div", { className: "d-flex gap-2" },
            h("button", { type: "button", className: "btn btn-sm btn-outline-secondary btn-icon", disabled: !state.cluster, onClick: () => onCollectCluster(state.cluster) }, h(Icon, { name: "radar" }), "Collect"),
            h("button", { type: "button", className: "btn btn-sm btn-outline-secondary btn-icon", onClick: onRefresh }, h(Icon, { name: "arrow-clockwise" }), "Reload"),
            h("button", { type: "button", className: "btn btn-sm btn-outline-secondary", onClick: onClose }, "Close")
          )
        ),
        h("div", { className: "console-modal-body" }, body)
      )
    );
  }

  function SummaryBox({ label, value }) {
    return h("div", { className: "summary-box" },
      h("div", { className: "small text-muted fw-semibold" }, label),
      h("div", { className: "summary-value" }, value)
    );
  }

  function EvidenceRequestTable({ items, onLoadEvidence }) {
    if (!items.length) return h(EmptyState, { message: "No evidence requests." });
    return h("div", { className: "table-responsive" },
      h("table", { className: "table table-sm mb-0" },
        h("thead", null, h("tr", null,
          h("th", null, "Request"),
          h("th", null, "Node"),
          h("th", null, "Alert"),
          h("th", null, "Status"),
          h("th", null, "Created"),
          h("th", { className: "text-end" }, "Data")
        )),
        h("tbody", null, items.slice(0, 8).map((item) => h("tr", { key: item.request_id },
          h("td", { className: "font-monospace small" }, item.request_id),
          h("td", { className: "font-monospace small" }, item.node_name),
          h("td", null, item.alert_name),
          h("td", null, h(StatusBadge, { value: item.status, tone: evidenceStatusTone(item.status) })),
          h("td", null, formatDate(item.created_at)),
          h("td", { className: "text-end" },
            h("button", {
              type: "button",
              className: "btn btn-sm btn-outline-secondary",
              disabled: !item.evidence_id,
              onClick: () => onLoadEvidence(item.evidence_id),
            }, "View")
          )
        )))
      )
    );
  }

  function EvidenceBundlePreview({ state }) {
    if (state.evidenceLoading) return h(EmptyState, { message: "Loading evidence bundle." });
    if (state.evidenceError) return h(EmptyState, { message: state.evidenceError });
    const evidence = state.selectedEvidence;
    if (!evidence) return h(EmptyState, { message: "Select a completed evidence request." });
    const collectors = Object.keys(evidence.collectors || {});
    return h("div", { className: "evidence-preview" },
      h("div", { className: "detail-grid mb-2" },
        h("dl", { className: "detail-list mb-0" },
          h(DetailRow, { label: "Evidence", value: evidence.evidence_id || "n/a" }),
          h(DetailRow, { label: "Node", value: evidence.node_name || "n/a" })
        ),
        h("dl", { className: "detail-list mb-0" },
          h(DetailRow, { label: "Alert", value: evidence.alert_name || "n/a" }),
          h(DetailRow, { label: "Collectors", value: listValue(collectors) })
        )
      ),
      h("pre", { className: "code-block evidence-code" }, JSON.stringify(evidence.collectors || {}, null, 2))
    );
  }

  function ClusterReportList({ items }) {
    if (!items.length) return h(EmptyState, { message: "No RCA reports for this cluster." });
    return h("div", { className: "list-group list-group-flush" },
      items.slice(0, 5).map((report) => h("div", { key: report.report_id, className: "list-group-item px-0" },
        h("div", { className: "d-flex justify-content-between gap-2" },
          h("strong", { className: "small" }, report.summary?.symptom || report.report_id),
          h(StatusBadge, { value: report.summary?.confidence || "unknown", tone: confidenceTone(report.summary?.confidence) })
        ),
        h("div", { className: "small text-muted text-truncate" }, report.summary?.most_likely_cause || "n/a")
      ))
    );
  }

  function ActionConfirmDialog({ state, onCancel, onConfirm }) {
    const action = state.action || {};
    const report = state.report || {};
    return h("div", { className: "console-modal-backdrop", role: "presentation", onMouseDown: (event) => event.target === event.currentTarget && onCancel() },
      h("section", { className: "console-modal action-confirm-modal", role: "dialog", "aria-modal": "true" },
        h("div", { className: "console-modal-header" },
          h("div", null,
            h("h2", { className: "h5 mb-1" }, "Confirm Action"),
            h("div", { className: "small text-muted font-monospace" }, report.report_id)
          ),
          h(StatusBadge, { value: action.policy, tone: policyTone(action.policy) })
        ),
        h("div", { className: "console-modal-body d-grid gap-3" },
          h("div", { className: "action-card" },
            h("div", { className: "fw-semibold" }, action.action),
            h("div", { className: "small text-muted mt-1" }, action.reason || "No reason"),
            h("div", { className: "small text-muted mt-1" }, action.automation_allowed
              ? "This will request read-only follow-up evidence from the node agent."
              : "The policy gate will record the request status without direct node mutation.")
          ),
          Boolean(action.guardrails?.length) && h("div", { className: "alert alert-warning mb-0 py-2" }, `Guardrails: ${action.guardrails.join(", ")}`),
          state.error && h("div", { className: "alert alert-danger mb-0 py-2" }, state.error)
        ),
        h("div", { className: "console-modal-footer" },
          h("button", { type: "button", className: "btn btn-outline-secondary", onClick: onCancel, disabled: state.loading }, "Cancel"),
          h("button", { type: "button", className: "btn btn-primary btn-icon", onClick: onConfirm, disabled: state.loading },
            state.loading ? h(Icon, { name: "hourglass-split" }) : h(Icon, { name: "check2" }),
            state.loading ? "Processing" : "Confirm"
          )
        )
      )
    );
  }

  function CollectionConfirmDialog({ state, onCancel, onConfirm }) {
    const cluster = state.cluster || {};
    return h("div", { className: "console-modal-backdrop", role: "presentation", onMouseDown: (event) => event.target === event.currentTarget && onCancel() },
      h("section", { className: "console-modal action-confirm-modal", role: "dialog", "aria-modal": "true" },
        h("div", { className: "console-modal-header" },
          h("div", null,
            h("h2", { className: "h5 mb-1" }, "Confirm Collection"),
            h("div", { className: "small text-muted font-monospace" }, cluster.cluster_id || "n/a")
          ),
          h(StatusBadge, { value: "read-only", tone: "green" })
        ),
        h("div", { className: "console-modal-body d-grid gap-3" },
          h("div", { className: "action-card" },
            h("div", { className: "fw-semibold" }, cluster.name || "Cluster collection"),
            h("div", { className: "small text-muted mt-1" },
              "Backend will create read-only evidence requests for registered online node agents. Submitted evidence will be analyzed by the existing RCA pipeline."
            ),
            h("div", { className: "small text-muted mt-1" }, "No Prometheus or Alertmanager trigger is required.")
          ),
          state.error && h("div", { className: "alert alert-danger mb-0 py-2" }, state.error)
        ),
        h("div", { className: "console-modal-footer" },
          h("button", { type: "button", className: "btn btn-outline-secondary", onClick: onCancel, disabled: state.loading }, "Cancel"),
          h("button", { type: "button", className: "btn btn-primary btn-icon", onClick: onConfirm, disabled: state.loading },
            state.loading ? h(Icon, { name: "hourglass-split" }) : h(Icon, { name: "radar" }),
            state.loading ? "Requesting" : "Collect"
          )
        )
      )
    );
  }

  function ReportDetail({ detail, onPrepareAction }) {
    if (detail.loading) return h(EmptyState, { message: "Loading report detail." });
    if (detail.error) return h(EmptyState, { message: detail.error });
    const report = detail.report;
    const signals = section(report, "derived_signals")?.signals || [];
    const checklist = section(report, "resolution_checklist")?.items || [];
    const llm = section(report, "llm_analysis")?.analysis || {};
    return h("div", { className: "d-grid gap-3" },
      h("div", { className: "detail-grid" },
        h("dl", { className: "detail-list mb-0" },
          h(DetailRow, { label: "Report", value: report.report_id }),
          h(DetailRow, { label: "Nodes", value: listValue(report.scope?.nodes) }),
          h(DetailRow, { label: "Components", value: listValue(report.scope?.components) })
        ),
        h("dl", { className: "detail-list mb-0" },
          h(DetailRow, { label: "LLM status", value: llm.status || "unknown" }),
          h(DetailRow, { label: "Provider", value: llm.provider || "n/a" }),
          h(DetailRow, { label: "Reason", value: llm.reason || llm.error || "n/a" })
        )
      ),
      h("div", null, h("h3", { className: "h6" }, "Root Cause Candidates"), h(OrderedFacts, { items: report.root_cause_candidates || [], titleKey: "cause", metaKey: "confidence", textKey: "supporting_evidence" })),
      h("div", null, h("h3", { className: "h6" }, "Recommended Actions"), h(ActionFacts, { items: report.recommended_actions || [], report, onPrepareAction })),
      h("div", null, h("h3", { className: "h6" }, "Derived Signals"), h(SignalFacts, { items: signals })),
      h("div", null, h("h3", { className: "h6" }, "Resolution Checklist"), h(ChecklistFacts, { items: checklist }))
    );
  }

  function OrderedFacts({ items, titleKey, metaKey, textKey }) {
    if (!items.length) return h("div", { className: "empty-state" }, "No items.");
    return h("ol", { className: "d-grid gap-2 ps-3 mb-0" }, items.map((item, index) => h("li", { key: index },
      h("div", { className: "d-flex justify-content-between gap-2" },
        h("strong", null, item[titleKey]),
        h(StatusBadge, { value: item[metaKey], tone: confidenceTone(item[metaKey]) })
      ),
      h("div", { className: "small text-muted" }, listValue(item[textKey]))
    )));
  }

  function ActionFacts({ items, report, onPrepareAction }) {
    if (!items.length) return h("div", { className: "empty-state" }, "No actions.");
    return h("div", { className: "d-grid gap-2" }, items.map((item, index) => h("div", { key: index, className: "action-card" },
      h("div", { className: "d-flex justify-content-between gap-2 align-items-start" },
        h("strong", null, item.action),
        h("div", { className: "d-flex gap-2 flex-wrap justify-content-end" },
          h(StatusBadge, { value: item.policy, tone: policyTone(item.policy) }),
          h("button", {
            type: "button",
            className: `btn btn-sm ${item.automation_allowed ? "btn-primary" : "btn-outline-secondary"} btn-icon`,
            disabled: item.policy === "NEVER_AUTO_EXECUTE" || !onPrepareAction,
            onClick: () => onPrepareAction(report, item, index),
          }, h(Icon, { name: actionIcon(item) }), actionButtonLabel(item))
        )
      ),
      h("div", { className: "small text-muted mt-1" }, item.reason || "No reason"),
      h("div", { className: "small text-muted mt-1" }, `${item.automation_mode || "manual"} / automation allowed: ${item.automation_allowed}`),
      Boolean(item.guardrails?.length) && h("div", { className: "small text-muted mt-1" }, `Guardrails: ${item.guardrails.join(", ")}`)
    )));
  }

  function SignalFacts({ items }) {
    if (!items.length) return h("div", { className: "empty-state" }, "No signals.");
    return h("div", { className: "d-grid gap-2" }, items.map((item, index) => h("div", { key: index, className: "border rounded-2 p-2" },
      h("div", { className: "d-flex justify-content-between gap-2" }, h("strong", null, item.signal), h(StatusBadge, { value: item.severity, tone: item.severity === "critical" ? "red" : "amber" })),
      h("div", { className: "small text-muted mt-1" }, `${item.component}: ${item.interpretation}`),
      h("div", { className: "small text-muted mt-1" }, item.next_step)
    )));
  }

  function ChecklistFacts({ items }) {
    if (!items.length) return h("div", { className: "empty-state" }, "No checklist.");
    return h("div", { className: "d-grid gap-2" }, items.map((item, index) => h("div", { key: index, className: "border rounded-2 p-2" },
      h("div", { className: "fw-semibold" }, item.component),
      h("div", { className: "small text-muted" }, item.check),
      h("code", { className: "small d-block text-break mt-1" }, item.command)
    )));
  }

  function InputField({ label, ...props }) {
    const inputId = props.id || `field-${props.name || label.toLowerCase().replace(/[^a-z0-9]+/g, "-")}`;
    return h("div", { className: "col-12 col-md-6" },
      h("label", { className: "form-label", htmlFor: inputId }, label),
      h("input", { className: "form-control", ...props, id: inputId })
    );
  }

  function DetailRow({ label, value }) {
    return h("div", { className: "detail-row" }, h("dt", null, label), h("dd", null, value));
  }

  function StatusBadge({ value, tone }) {
    return h("span", { className: `badge badge-soft ${tone || ""}` }, value || "n/a");
  }

  function EmptyState({ message }) {
    return h("div", { className: "empty-state" }, message);
  }

  function Toast({ message, onClose }) {
    return h("div", { className: "toast-area" },
      h("div", { className: "toast show align-items-center text-bg-dark border-0", role: "status" },
        h("div", { className: "d-flex" },
          h("div", { className: "toast-body" }, message),
          h("button", { type: "button", className: "btn-close btn-close-white me-2 m-auto", onClick: onClose })
        )
      )
    );
  }

  function Icon({ name }) {
    return h("i", { className: `bi bi-${name}`, "aria-hidden": "true" });
  }

  function formPayload(form) {
    return Object.fromEntries([...new FormData(form).entries()].map(([key, value]) => {
      const normalized = typeof value === "string" ? value.trim() : value;
      return [key, normalized === "" ? null : normalized];
    }));
  }

  function readError(body, fallback) {
    const detail = body && typeof body === "object" ? body.detail : body;
    if (Array.isArray(detail)) return detail.map((item) => item.msg || String(item)).join(", ");
    if (detail && typeof detail === "object") return JSON.stringify(detail);
    return detail || fallback || "Request failed.";
  }

  function formatDate(value) {
    if (!value) return "n/a";
    return new Intl.DateTimeFormat("ko-KR", {
      month: "2-digit",
      day: "2-digit",
      hour: "2-digit",
      minute: "2-digit",
    }).format(new Date(value));
  }

  function formatAgentLastSeen(agent) {
    return formatDate(agent.last_heartbeat_at || agent.health?.freshness?.last_seen_at || agent.registered_at);
  }

  function uniquePolicies(report) {
    return [...new Set((report.recommended_actions || []).map((action) => action.policy).filter(Boolean))];
  }

  function confidenceTone(value) {
    if (value === "high") return "green";
    if (value === "medium") return "amber";
    return "red";
  }

  function clusterStatusTone(value) {
    if (value === "active") return "green";
    if (value === "agent_pending" || value === "registered") return "amber";
    return "blue";
  }

  function agentStatusTone(value) {
    if (value === "healthy") return "green";
    if (value === "offline") return "red";
    if (value === "degraded") return "amber";
    return "blue";
  }

  function evidenceStatusTone(value) {
    if (value === "completed") return "green";
    if (value === "failed") return "red";
    if (value === "pending") return "amber";
    return "blue";
  }

  function policyTone(value) {
    if (value === "AUTO_SAFE") return "green";
    if (value === "NEVER_AUTO_EXECUTE") return "red";
    if (value === "APPROVAL_REQUIRED" || value === "GITOPS_PR_ONLY") return "amber";
    return "blue";
  }

  function actionButtonLabel(action) {
    if (action.policy === "AUTO_SAFE" && action.automation_allowed) return "Execute";
    if (action.policy === "APPROVAL_REQUIRED") return "Request";
    if (action.policy === "GITOPS_PR_ONLY") return "PR Gate";
    if (action.policy === "NEVER_AUTO_EXECUTE") return "Blocked";
    return "Review";
  }

  function actionIcon(action) {
    if (action.policy === "AUTO_SAFE" && action.automation_allowed) return "play-circle";
    if (action.policy === "APPROVAL_REQUIRED") return "shield-check";
    if (action.policy === "GITOPS_PR_ONLY") return "git";
    if (action.policy === "NEVER_AUTO_EXECUTE") return "slash-circle";
    return "eye";
  }

  function section(report, type) {
    return (report.evidence || []).find((item) => item.type === type);
  }

  function listValue(value) {
    if (Array.isArray(value)) return value.length ? value.join(", ") : "n/a";
    if (value === null || value === undefined || value === "") return "n/a";
    return String(value);
  }

  ReactDOM.createRoot(rootElement).render(h(App));
})();
