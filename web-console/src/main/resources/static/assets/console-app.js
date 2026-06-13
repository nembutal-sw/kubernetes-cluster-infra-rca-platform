(function () {
  const rootElement = document.getElementById("rca-console-root");
  const h = React.createElement;
  const apiBase = rootElement.dataset.apiBase || "/console-api";
  const publicApiBase = rootElement.dataset.publicApiBase || window.location.origin;
  const views = [
    { id: "overview", label: "Overview", icon: "speedometer2" },
    { id: "access", label: "Access", icon: "person-check" },
    { id: "clusters", label: "Clusters", icon: "hdd-network" },
    { id: "webhooks", label: "Webhooks", icon: "diagram-3" },
    { id: "reports", label: "Reports", icon: "clipboard2-pulse" },
    { id: "settings", label: "Settings", icon: "sliders" },
  ];

  function App() {
    const [activeView, setActiveView] = React.useState("overview");
    const [clusters, setClusters] = React.useState([]);
    const [reports, setReports] = React.useState([]);
    const [pendingUsers, setPendingUsers] = React.useState([]);
    const [reportDetails, setReportDetails] = React.useState({});
    const [agentsByCluster, setAgentsByCluster] = React.useState({});
    const [installCommands, setInstallCommands] = React.useState({});
    const [sessionToken, setSessionToken] = React.useState(sessionStorage.getItem("rca_session_token") || "");
    const [adminToken, setAdminToken] = React.useState(sessionStorage.getItem("rca_admin_token") || "");
    const [currentUser, setCurrentUser] = React.useState(null);
    const [toast, setToast] = React.useState("");
    const [loading, setLoading] = React.useState({});
    const [autoRefresh, setAutoRefresh] = React.useState(true);
    const [lastRefresh, setLastRefresh] = React.useState(null);

    const notify = React.useCallback((message) => {
      setToast(message);
      window.clearTimeout(notify.timer);
      notify.timer = window.setTimeout(() => setToast(""), 3200);
    }, []);

    const authHeaders = React.useCallback((allowAdminFallback) => {
      const headers = {};
      if (sessionToken) headers.Authorization = `Bearer ${sessionToken}`;
      if (allowAdminFallback && adminToken) headers["X-Admin-Token"] = adminToken;
      return headers;
    }, [sessionToken, adminToken]);

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
        return;
      }
      try {
        const user = await callApi("/api/auth/me", { headers: authHeaders(false) });
        setCurrentUser(user);
      } catch (error) {
        setSessionToken("");
        setCurrentUser(null);
        sessionStorage.removeItem("rca_session_token");
        if (!silent) notify(error.message);
      }
    }, [sessionToken, authHeaders, callApi, notify]);

    const loadClusters = React.useCallback(async (silent) => {
      try {
        setLoading((value) => ({ ...value, clusters: true }));
        const result = await callApi("/api/clusters", { headers: authHeaders(true) });
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
        const result = await callApi("/api/rca/reports", { headers: authHeaders(true) });
        setReports(Array.isArray(result) ? result : []);
      } catch (error) {
        setReports([]);
        if (!silent) notify(error.message);
      } finally {
        setLoading((value) => ({ ...value, reports: false }));
      }
    }, [authHeaders, callApi, notify]);

    const loadPendingUsers = React.useCallback(async (silent) => {
      if (!sessionToken && !adminToken) {
        setPendingUsers([]);
        return;
      }
      try {
        setLoading((value) => ({ ...value, users: true }));
        const result = await callApi("/api/admin/users?status=pending_approval", { headers: authHeaders(true) });
        setPendingUsers(Array.isArray(result) ? result : []);
      } catch (error) {
        setPendingUsers([]);
        if (!silent) notify(error.message);
      } finally {
        setLoading((value) => ({ ...value, users: false }));
      }
    }, [sessionToken, adminToken, authHeaders, callApi, notify]);

    const refreshAll = React.useCallback(async (silent) => {
      await Promise.allSettled([
        loadClusters(silent),
        loadReports(silent),
        loadPendingUsers(true),
      ]);
      setLastRefresh(new Date());
    }, [loadClusters, loadReports, loadPendingUsers]);

    React.useEffect(() => {
      loadCurrentUser(true).finally(() => refreshAll(true));
    }, [loadCurrentUser, refreshAll]);

    React.useEffect(() => {
      if (!autoRefresh) return undefined;
      const timer = window.setInterval(() => refreshAll(true), 30000);
      return () => window.clearInterval(timer);
    }, [autoRefresh, refreshAll]);

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
        await refreshAll(true);
      } catch (error) {
        notify(error.message);
      }
    }

    async function logout() {
      if (sessionToken) {
        await callApi("/api/auth/logout", {
          method: "POST",
          headers: authHeaders(false),
        }).catch(() => null);
      }
      setSessionToken("");
      setCurrentUser(null);
      setClusters([]);
      setReports([]);
      setPendingUsers([]);
      setReportDetails({});
      setAgentsByCluster({});
      sessionStorage.removeItem("rca_session_token");
      notify("Signed out.");
    }

    function saveAdminToken(value) {
      const normalized = value.trim();
      setAdminToken(normalized);
      if (normalized) {
        sessionStorage.setItem("rca_admin_token", normalized);
        notify("Admin token stored for this browser session.");
      } else {
        sessionStorage.removeItem("rca_admin_token");
        notify("Admin token cleared.");
      }
    }

    async function submitSignup(event) {
      event.preventDefault();
      const form = event.currentTarget;
      try {
        const user = await callApi("/api/auth/signup", {
          method: "POST",
          body: JSON.stringify(formPayload(form)),
        });
        form.reset();
        notify(`Signup requested: ${user.email}`);
        await loadPendingUsers(true);
      } catch (error) {
        notify(error.message);
      }
    }

    async function decideUser(userId, decision) {
      const role = document.querySelector(`[data-role-for="${userId}"]`)?.value || "viewer";
      const note = document.querySelector(`[data-note-for="${userId}"]`)?.value || null;
      try {
        await callApi(`/api/admin/users/${encodeURIComponent(userId)}/approval`, {
          method: "POST",
          headers: authHeaders(true),
          body: JSON.stringify({ decision, role: decision === "approve" ? role : null, note }),
        });
        notify(decision === "approve" ? "User approved." : "User rejected.");
        await loadPendingUsers(false);
      } catch (error) {
        notify(error.message);
      }
    }

    async function createCluster(event) {
      event.preventDefault();
      const form = event.currentTarget;
      try {
        const cluster = await callApi("/api/clusters", {
          method: "POST",
          headers: authHeaders(true),
          body: JSON.stringify(formPayload(form)),
        });
        form.reset();
        notify(`Cluster registered: ${cluster.name}`);
        await loadClusters(false);
      } catch (error) {
        notify(error.message);
      }
    }

    async function loadInstallCommand(clusterId) {
      try {
        setInstallCommands((value) => ({ ...value, [clusterId]: "Loading..." }));
        const query = new URLSearchParams({ backend_url: publicApiBase });
        const response = await callApi(`/api/clusters/${encodeURIComponent(clusterId)}/install-command?${query}`, {
          headers: authHeaders(true),
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
          headers: authHeaders(true),
        });
        setAgentsByCluster((value) => ({ ...value, [clusterId]: { loading: false, items: agents } }));
      } catch (error) {
        setAgentsByCluster((value) => ({ ...value, [clusterId]: { loading: false, error: error.message, items: [] } }));
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
          headers: authHeaders(true),
        });
        setReportDetails((value) => ({ ...value, [reportId]: { open: true, loading: false, report } }));
      } catch (error) {
        setReportDetails((value) => ({ ...value, [reportId]: { open: true, loading: false, error: error.message } }));
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

    const signedIn = Boolean(currentUser);
    const canUsePrivilegedApi = Boolean(sessionToken || adminToken);
    const webhookEndpoint = `${publicApiBase.replace(/\/$/, "")}/api/webhooks/alertmanager`;

    return h("div", { className: "console-shell" },
      h(Sidebar, { activeView, setActiveView }),
      h("main", { className: "console-main" },
        h(Topbar, {
          currentUser,
          signedIn,
          adminToken,
          autoRefresh,
          lastRefresh,
          onLogin: login,
          onLogout: logout,
          onSaveAdminToken: saveAdminToken,
          onRefresh: () => refreshAll(false),
          onToggleAutoRefresh: () => setAutoRefresh((value) => !value),
        }),
        activeView === "overview" && h(OverviewView, {
          clusters,
          reports,
          pendingUsers,
          loading,
          webhookEndpoint,
          canUsePrivilegedApi,
          onNavigate: setActiveView,
        }),
        activeView === "access" && h(AccessView, {
          pendingUsers,
          loading,
          onSubmitSignup: submitSignup,
          onDecideUser: decideUser,
          onLoadUsers: () => loadPendingUsers(false),
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
          onCopy: copyText,
        }),
        activeView === "settings" && h(SettingsView, {
          apiBase,
          publicApiBase,
          canUsePrivilegedApi,
          autoRefresh,
        }),
        toast && h("div", { className: "toast-area" },
          h("div", { className: "toast show align-items-center text-bg-dark border-0", role: "status" },
            h("div", { className: "d-flex" },
              h("div", { className: "toast-body" }, toast),
              h("button", { type: "button", className: "btn-close btn-close-white me-2 m-auto", onClick: () => setToast("") })
            )
          )
        )
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
    const [adminTokenInput, setAdminTokenInput] = React.useState(props.adminToken || "");
    React.useEffect(() => setAdminTokenInput(props.adminToken || ""), [props.adminToken]);
    return h("section", { className: "console-topbar" },
      h("div", { className: "row g-3 align-items-end" },
        h("div", { className: "col-12 col-xl-4" },
          h("div", { className: "d-flex align-items-center gap-2 mb-1" },
            h("span", { className: `status-dot ${props.signedIn ? "online" : props.adminToken ? "warn" : ""}` }),
            h("span", { className: "small text-muted fw-semibold" }, props.currentUser ? `${props.currentUser.email} / ${props.currentUser.role}` : props.adminToken ? "Admin token mode" : "Signed out")
          ),
          h("h1", { className: "h4 mb-0" }, "Cluster Infrastructure RCA")
        ),
        h("div", { className: "col-12 col-xl-5" },
          props.signedIn
            ? h("div", { className: "d-flex gap-2 flex-wrap" },
                h("button", { type: "button", className: "btn btn-outline-secondary btn-icon", onClick: props.onLogout }, h(Icon, { name: "box-arrow-right" }), "Logout"),
                h("button", { type: "button", className: "btn btn-outline-secondary btn-icon", onClick: props.onRefresh }, h(Icon, { name: "arrow-clockwise" }), "Refresh"),
                h("button", { type: "button", className: `btn btn-outline-secondary btn-icon ${props.autoRefresh ? "active" : ""}`, onClick: props.onToggleAutoRefresh }, h(Icon, { name: "activity" }), props.autoRefresh ? "Auto" : "Manual")
              )
            : h("form", { className: "row g-2", onSubmit: props.onLogin },
                h("div", { className: "col-12 col-md-5" },
                  h("label", { className: "form-label" }, "Email"),
                  h("input", { className: "form-control", name: "email", type: "email", autoComplete: "email", required: true })
                ),
                h("div", { className: "col-12 col-md-5" },
                  h("label", { className: "form-label" }, "Password"),
                  h("input", { className: "form-control", name: "password", type: "password", autoComplete: "current-password", required: true })
                ),
                h("div", { className: "col-12 col-md-2 d-grid" },
                  h("button", { className: "btn btn-primary btn-icon", type: "submit" }, h(Icon, { name: "box-arrow-in-right" }), "Login")
                )
              )
        ),
        h("div", { className: "col-12 col-xl-3" },
          h("label", { className: "form-label" }, "Bootstrap admin token"),
          h("div", { className: "input-group" },
            h("input", {
              className: "form-control",
              type: "password",
              value: adminTokenInput,
              autoComplete: "off",
              onChange: (event) => setAdminTokenInput(event.target.value),
            }),
            h("button", { className: "btn btn-outline-secondary", type: "button", onClick: () => props.onSaveAdminToken(adminTokenInput) }, "Use")
          ),
          h("div", { className: "small text-muted mt-1" }, props.lastRefresh ? `Last refresh ${formatDate(props.lastRefresh)}` : "Not refreshed")
        )
      )
    );
  }

  function OverviewView({ clusters, reports, pendingUsers, loading, webhookEndpoint, canUsePrivilegedApi, onNavigate }) {
    const highConfidence = reports.filter((report) => report.summary?.confidence === "high").length;
    return h("div", { className: "d-grid gap-3" },
      h("div", { className: "row g-3" },
        h(MetricTile, { label: "Clusters", value: clusters.length, hint: loading.clusters ? "Loading" : "Registered targets", icon: "hdd-network" }),
        h(MetricTile, { label: "RCA Reports", value: reports.length, hint: `${highConfidence} high confidence`, icon: "clipboard2-pulse" }),
        h(MetricTile, { label: "Approvals", value: pendingUsers.length, hint: canUsePrivilegedApi ? "Pending users" : "Auth required", icon: "person-check" }),
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

  function AccessView({ pendingUsers, loading, onSubmitSignup, onDecideUser, onLoadUsers }) {
    return h("div", { className: "row g-3" },
      h("div", { className: "col-12 col-xl-5" },
        h(Panel, { title: "Signup Request", subtitle: "Final approval is required" },
          h("form", { className: "row g-3", onSubmit: onSubmitSignup },
            h(InputField, { label: "Email", name: "email", type: "email", required: true }),
            h(InputField, { label: "Full name", name: "full_name", required: true }),
            h(InputField, { label: "Password", name: "password", type: "password", minLength: 8, required: true }),
            h("div", { className: "col-12 col-md-6" },
              h("label", { className: "form-label" }, "Requested role"),
              h("select", { className: "form-select", name: "requested_role", defaultValue: "viewer" },
                h("option", { value: "viewer" }, "Viewer"),
                h("option", { value: "operator" }, "Operator"),
                h("option", { value: "admin" }, "Admin")
              )
            ),
            h("div", { className: "col-12" },
              h("label", { className: "form-label" }, "Reason"),
              h("textarea", { className: "form-control", name: "reason", rows: 4 })
            ),
            h("div", { className: "col-12 d-grid" },
              h("button", { className: "btn btn-primary btn-icon", type: "submit" }, h(Icon, { name: "send" }), "Request")
            )
          )
        )
      ),
      h("div", { className: "col-12 col-xl-7" },
        h(Panel, {
          title: "Approval Queue",
          subtitle: loading.users ? "Loading" : `${pendingUsers.length} pending`,
          action: h("button", { className: "btn btn-sm btn-outline-secondary btn-icon", onClick: onLoadUsers }, h(Icon, { name: "arrow-clockwise" }), "Load"),
        }, pendingUsers.length ? h("div", { className: "table-responsive" },
          h("table", { className: "table table-hover mb-0" },
            h("thead", null, h("tr", null,
              h("th", null, "User"),
              h("th", null, "Role"),
              h("th", null, "Note"),
              h("th", { className: "text-end" }, "Decision")
            )),
            h("tbody", null, pendingUsers.map((user) => h("tr", { key: user.user_id },
              h("td", null,
                h("div", { className: "fw-semibold" }, user.full_name),
                h("div", { className: "small text-muted" }, user.email),
                h("div", { className: "small text-muted text-truncate-cell" }, user.reason || "No reason")
              ),
              h("td", null,
                h("select", { className: "form-select form-select-sm", "data-role-for": user.user_id, defaultValue: user.requested_role || "viewer" },
                  h("option", { value: "viewer" }, "Viewer"),
                  h("option", { value: "operator" }, "Operator"),
                  h("option", { value: "admin" }, "Admin")
                )
              ),
              h("td", null, h("input", { className: "form-control form-control-sm", "data-note-for": user.user_id })),
              h("td", { className: "text-end" },
                h("div", { className: "btn-group btn-group-sm" },
                  h("button", { className: "btn btn-outline-success", onClick: () => onDecideUser(user.user_id, "approve") }, "Approve"),
                  h("button", { className: "btn btn-outline-danger", onClick: () => onDecideUser(user.user_id, "reject") }, "Reject")
                )
              )
            )))
          )
        ) : h(EmptyState, { message: "No pending users loaded." }))
      )
    );
  }

  function ClustersView(props) {
    return h("div", { className: "d-grid gap-3" },
      h("div", { className: "row g-3" },
        h("div", { className: "col-12 col-xl-4" },
          h(Panel, { title: "Register Cluster", subtitle: "Create bootstrap token" },
            h("form", { className: "row g-3", onSubmit: props.onCreateCluster },
              h(InputField, { label: "Cluster name", name: "name", required: true, placeholder: "prod-cluster" }),
              h("div", { className: "col-12" },
                h("label", { className: "form-label" }, "Environment"),
                h("select", { className: "form-select", name: "environment", defaultValue: "prod" },
                  h("option", { value: "prod" }, "prod"),
                  h("option", { value: "stage" }, "stage"),
                  h("option", { value: "dev" }, "dev")
                )
              ),
              h("div", { className: "col-12" },
                h("label", { className: "form-label" }, "Description"),
                h("textarea", { className: "form-control", name: "description", rows: 4 })
              ),
              h("div", { className: "col-12 d-grid" },
                h("button", { className: "btn btn-primary btn-icon", type: "submit" }, h(Icon, { name: "plus-lg" }), "Register")
              )
            )
          )
        ),
        h("div", { className: "col-12 col-xl-8" },
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
                    h("div", { className: "fw-semibold" }, cluster.name),
                    h("div", { className: "small text-muted font-monospace" }, cluster.cluster_id)
                  ),
                  h("td", null, cluster.environment),
                  h("td", null, h(StatusBadge, { value: cluster.status, tone: cluster.status === "active" ? "green" : "amber" })),
                  h("td", { className: "text-end" },
                    h("div", { className: "btn-group btn-group-sm" },
                      h("button", { className: "btn btn-outline-secondary", onClick: () => props.onLoadInstallCommand(cluster.cluster_id) }, "Install"),
                      h("button", { className: "btn btn-outline-secondary", onClick: () => props.onLoadAgents(cluster.cluster_id) }, "Agents"),
                      h("a", {
                        className: "btn btn-outline-secondary",
                        href: `${apiBase}/api/clusters/${encodeURIComponent(cluster.cluster_id)}/agent-manifest?backend_url=${encodeURIComponent(props.publicApiBase)}`,
                        target: "_blank",
                        rel: "noreferrer",
                      }, "Manifest")
                    )
                  )
                ),
                (props.installCommands[cluster.cluster_id] || props.agentsByCluster[cluster.cluster_id]) && h("tr", null,
                  h("td", { colSpan: 4 },
                    props.installCommands[cluster.cluster_id] && h("pre", { className: "code-block mb-3" }, props.installCommands[cluster.cluster_id]),
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

  function ReportsView({ reports, loading, reportDetails, onLoadReports, onToggleReport, onCopy }) {
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
            detail?.open && h("tr", null, h("td", { colSpan: 5 }, h(ReportDetail, { detail })))
          );
        }))
      )
    ) : h(EmptyState, { message: "No reports loaded." }));
  }

  function SettingsView({ apiBase, publicApiBase, canUsePrivilegedApi, autoRefresh }) {
    const rows = [
      ["Console proxy", apiBase],
      ["Public API", publicApiBase],
      ["Auth state", canUsePrivilegedApi ? "available" : "required"],
      ["Refresh mode", autoRefresh ? "auto / 30s" : "manual"],
      ["Admin token env", "RCA_ADMIN_APPROVAL_TOKEN"],
      ["Webhook token env", "RCA_WEBHOOK_TOKEN"],
      ["LLM provider env", "RCA_LLM_PROVIDER"],
      ["Database env", "RCA_DATABASE_URL"],
    ];
    return h(Panel, { title: "Console Settings", subtitle: "Runtime references" },
      h("div", { className: "row g-3" },
        rows.map(([label, value]) => h("div", { className: "col-12 col-md-6 col-xl-3", key: label },
          h("div", { className: "border rounded-2 p-3 bg-light h-100" },
            h("div", { className: "small text-muted fw-semibold mb-2" }, label),
            h("code", { className: "small text-break" }, value)
          )
        ))
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
          h("td", null, h(StatusBadge, { value: cluster.status, tone: cluster.status === "active" ? "green" : "amber" }))
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
        h("thead", null, h("tr", null, h("th", null, "Node"), h("th", null, "Status"), h("th", null, "Version"), h("th", null, "Last heartbeat"))),
        h("tbody", null, state.items.map((agent) => h("tr", { key: agent.node_name },
          h("td", { className: "font-monospace small" }, agent.node_name),
          h("td", null, h(StatusBadge, { value: agent.status || "unknown", tone: agent.status === "active" ? "green" : "amber" })),
          h("td", null, agent.agent_version || "n/a"),
          h("td", null, formatDate(agent.last_heartbeat_at))
        )))
      )
    );
  }

  function ReportDetail({ detail }) {
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
      h("div", null, h("h3", { className: "h6" }, "Recommended Actions"), h(ActionFacts, { items: report.recommended_actions || [] })),
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

  function ActionFacts({ items }) {
    if (!items.length) return h("div", { className: "empty-state" }, "No actions.");
    return h("div", { className: "d-grid gap-2" }, items.map((item, index) => h("div", { key: index, className: "border rounded-2 p-2" },
      h("div", { className: "d-flex justify-content-between gap-2" }, h("strong", null, item.action), h(StatusBadge, { value: item.policy, tone: policyTone(item.policy) })),
      h("div", { className: "small text-muted mt-1" }, item.reason || "No reason"),
      h("div", { className: "small text-muted mt-1" }, `${item.automation_mode || "manual"} / automation allowed: ${item.automation_allowed}`)
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
    return h("div", { className: "col-12 col-md-6" },
      h("label", { className: "form-label" }, label),
      h("input", { className: "form-control", ...props })
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

  function uniquePolicies(report) {
    return [...new Set((report.recommended_actions || []).map((action) => action.policy).filter(Boolean))];
  }

  function confidenceTone(value) {
    if (value === "high") return "green";
    if (value === "medium") return "amber";
    return "red";
  }

  function policyTone(value) {
    if (value === "AUTO_SAFE") return "green";
    if (value === "NEVER_AUTO_EXECUTE") return "red";
    if (value === "APPROVAL_REQUIRED" || value === "GITOPS_PR_ONLY") return "amber";
    return "blue";
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
