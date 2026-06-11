const state = {
  clusters: [],
  reports: [],
  reportDetails: {},
  expandedReportId: "",
  pendingUsers: [],
  sessionToken: sessionStorage.getItem("rca_session_token") || "",
  currentUser: null,
  adminToken: sessionStorage.getItem("rca_admin_token") || "",
};

const $ = (selector) => document.querySelector(selector);

document.addEventListener("DOMContentLoaded", () => {
  $("#adminToken").value = state.adminToken;
  $("#webhookEndpoint").textContent = `${window.location.origin}/api/webhooks/alertmanager`;

  $("#loginForm").addEventListener("submit", login);
  $("#logoutButton").addEventListener("click", logout);
  $("#saveTokenButton").addEventListener("click", saveAdminToken);
  $("#refreshButton").addEventListener("click", refreshAll);
  $("#loadUsersButton").addEventListener("click", loadPendingUsers);
  $("#loadReportsButton").addEventListener("click", loadReports);
  $("#copyWebhookButton").addEventListener("click", copyWebhookEndpoint);
  $("#signupForm").addEventListener("submit", submitSignup);
  $("#clusterForm").addEventListener("submit", submitCluster);

  document.querySelectorAll(".nav-link").forEach((link) => {
    link.addEventListener("click", () => setActiveNav(link));
  });

  loadCurrentUser({ silent: true }).finally(refreshAll);
});

async function refreshAll() {
  await Promise.allSettled([loadClusters(), loadReports(), loadPendingUsers({ silent: true })]);
  renderOverview();
}

async function login(event) {
  event.preventDefault();
  const form = event.currentTarget;
  const payload = formPayload(form);
  try {
    const session = await api("/api/auth/login", {
      method: "POST",
      body: JSON.stringify(payload),
    });
    state.sessionToken = session.access_token;
    state.currentUser = session.user;
    sessionStorage.setItem("rca_session_token", state.sessionToken);
    $("#loginPassword").value = "";
    renderSession();
    toast(`Signed in as ${session.user.email}.`);
    await refreshAll();
  } catch (error) {
    toast(error.message);
  }
}

async function logout() {
  if (state.sessionToken) {
    await api("/api/auth/logout", {
      method: "POST",
      headers: authHeaders(),
    }).catch(() => null);
  }
  state.sessionToken = "";
  state.currentUser = null;
  sessionStorage.removeItem("rca_session_token");
  renderSession();
  state.clusters = [];
  state.reports = [];
  state.reportDetails = {};
  state.expandedReportId = "";
  state.pendingUsers = [];
  renderClusters();
  renderReports();
  renderPendingUsers("Sign in or use bootstrap admin token.");
  renderOverview();
  toast("Signed out.");
}

async function loadCurrentUser(options = {}) {
  if (!state.sessionToken) {
    renderSession();
    return;
  }
  try {
    state.currentUser = await api("/api/auth/me", {
      headers: authHeaders(),
    });
  } catch (error) {
    state.sessionToken = "";
    state.currentUser = null;
    sessionStorage.removeItem("rca_session_token");
    if (!options.silent) toast(error.message);
  } finally {
    renderSession();
  }
}

function saveAdminToken() {
  state.adminToken = $("#adminToken").value.trim();
  if (state.adminToken) {
    sessionStorage.setItem("rca_admin_token", state.adminToken);
    toast("Admin token stored for this session.");
  } else {
    sessionStorage.removeItem("rca_admin_token");
    toast("Admin token cleared.");
  }
  refreshAll();
}

async function submitSignup(event) {
  event.preventDefault();
  const form = event.currentTarget;
  const payload = formPayload(form);
  try {
    const user = await api("/api/auth/signup", {
      method: "POST",
      body: JSON.stringify(payload),
    });
    form.reset();
    toast(`Signup request created for ${user.email}.`);
    await loadPendingUsers({ silent: true });
  } catch (error) {
    toast(error.message);
  }
}

async function submitCluster(event) {
  event.preventDefault();
  if (!ensureAuth()) return;
  const form = event.currentTarget;
  const payload = formPayload(form);
  try {
    const cluster = await api("/api/clusters", {
      method: "POST",
      headers: authHeaders({ allowAdminFallback: true }),
      body: JSON.stringify(payload),
    });
    form.reset();
    toast(`Cluster registered: ${cluster.name}`);
    await loadClusters();
  } catch (error) {
    toast(error.message);
  }
}

async function loadClusters() {
  try {
    state.clusters = await api("/api/clusters", {
      headers: authHeaders({ allowAdminFallback: true }),
    });
    renderClusters();
  } catch (error) {
    renderError("#clusterList", error.message);
  } finally {
    renderOverview();
  }
}

async function loadReports() {
  try {
    state.reports = await api("/api/rca/reports", {
      headers: authHeaders({ allowAdminFallback: true }),
    });
    renderReports();
  } catch (error) {
    renderError("#reportList", error.message);
  } finally {
    renderOverview();
  }
}

async function loadPendingUsers(options = {}) {
  if (!state.sessionToken && !state.adminToken) {
    state.pendingUsers = [];
    renderPendingUsers("Admin role or bootstrap token required.");
    renderOverview();
    return;
  }

  try {
    const query = new URLSearchParams({ status: "pending_approval" });
    state.pendingUsers = await api(`/api/admin/users?${query.toString()}`, {
      headers: authHeaders({ allowAdminFallback: true }),
    });
    renderPendingUsers();
  } catch (error) {
    state.pendingUsers = [];
    renderPendingUsers(options.silent ? "Admin role or bootstrap token required." : error.message);
    if (!options.silent) toast(error.message);
  } finally {
    renderOverview();
  }
}

async function approveUser(userId, decision) {
  if (!ensureAuth()) {
    return;
  }
  const roleSelect = document.querySelector(`[data-role-for="${userId}"]`);
  const noteInput = document.querySelector(`[data-note-for="${userId}"]`);
  try {
    await api(`/api/admin/users/${userId}/approval`, {
      method: "POST",
      headers: authHeaders({ allowAdminFallback: true }),
      body: JSON.stringify({
        decision,
        role: decision === "approve" ? roleSelect.value : null,
        note: noteInput.value || null,
      }),
    });
    toast(decision === "approve" ? "User approved." : "User rejected.");
    await loadPendingUsers();
  } catch (error) {
    toast(error.message);
  }
}

async function loadInstallCommand(clusterId, targetId) {
  if (!ensureAuth()) return;
  const backendUrl = window.location.origin;
  const query = new URLSearchParams({ backend_url: backendUrl });
  try {
    const response = await api(`/api/clusters/${clusterId}/install-command?${query.toString()}`, {
      headers: authHeaders({ allowAdminFallback: true }),
    });
    const target = document.getElementById(targetId);
    target.innerHTML = `<pre class="code-block"><code>${escapeHtml(response.commands.join("\n"))}</code></pre>`;
  } catch (error) {
    toast(error.message);
  }
}

function renderOverview() {
  $("#clusterCount").textContent = state.clusters.length;
  $("#reportCount").textContent = state.reports.length;
  $("#pendingUserCount").textContent = state.pendingUsers.length;
}

function renderClusters() {
  const target = $("#clusterList");
  if (!state.clusters.length) {
    target.className = "list empty";
    target.textContent = "No registered clusters.";
    return;
  }

  target.className = "list";
  target.innerHTML = state.clusters
    .map((cluster) => {
      const commandId = `install-${cluster.cluster_id}`;
      return `
        <article class="item">
          <div class="item-head">
            <div>
              <div class="item-title">${escapeHtml(cluster.name)}</div>
              <div class="item-meta">
                <span>${escapeHtml(cluster.cluster_id)}</span>
                <span>${escapeHtml(cluster.environment)}</span>
                <span>${cluster.description ? escapeHtml(cluster.description) : "no description"}</span>
              </div>
            </div>
            <span class="badge ${cluster.status === "active" ? "green" : "amber"}">${escapeHtml(cluster.status)}</span>
          </div>
          <div class="item-actions">
            <button type="button" data-install="${cluster.cluster_id}" data-target="${commandId}">Install command</button>
            <a class="badge" href="/api/clusters/${encodeURIComponent(cluster.cluster_id)}/agent-manifest?backend_url=${encodeURIComponent(window.location.origin)}" target="_blank" rel="noreferrer">Manifest</a>
          </div>
          <div id="${commandId}" class="command-slot"></div>
        </article>
      `;
    })
    .join("");

  target.querySelectorAll("[data-install]").forEach((button) => {
    button.addEventListener("click", () => loadInstallCommand(button.dataset.install, button.dataset.target));
  });
}

function renderPendingUsers(message = "No pending users.") {
  const target = $("#pendingUsers");
  if (!state.pendingUsers.length) {
    target.className = "list empty";
    target.textContent = message;
    return;
  }

  target.className = "list";
  target.innerHTML = state.pendingUsers
    .map(
      (user) => `
        <article class="item">
          <div class="item-head">
            <div>
              <div class="item-title">${escapeHtml(user.full_name)}</div>
              <div class="item-meta">
                <span>${escapeHtml(user.email)}</span>
                <span>requested: ${escapeHtml(user.requested_role)}</span>
                <span>${formatDate(user.created_at)}</span>
              </div>
            </div>
            <span class="badge amber">${escapeHtml(user.status)}</span>
          </div>
          <div class="item-meta">${escapeHtml(user.reason || "no reason provided")}</div>
          <div class="form-grid approval-grid">
            <label>
              Role
              <select data-role-for="${user.user_id}">
                <option value="viewer" ${user.requested_role === "viewer" ? "selected" : ""}>Viewer</option>
                <option value="operator" ${user.requested_role === "operator" ? "selected" : ""}>Operator</option>
                <option value="admin" ${user.requested_role === "admin" ? "selected" : ""}>Admin</option>
              </select>
            </label>
            <label>
              Note
              <input data-note-for="${user.user_id}" placeholder="approval note" />
            </label>
          </div>
          <div class="item-actions">
            <button class="primary" type="button" data-approve="${user.user_id}">Approve</button>
            <button type="button" data-reject="${user.user_id}">Reject</button>
          </div>
        </article>
      `,
    )
    .join("");

  target.querySelectorAll("[data-approve]").forEach((button) => {
    button.addEventListener("click", () => approveUser(button.dataset.approve, "approve"));
  });
  target.querySelectorAll("[data-reject]").forEach((button) => {
    button.addEventListener("click", () => approveUser(button.dataset.reject, "reject"));
  });
}

function renderReports() {
  const target = $("#reportList");
  if (!state.reports.length) {
    target.className = "list empty";
    target.textContent = "No RCA reports.";
    return;
  }

  target.className = "list";
  target.innerHTML = state.reports
    .map((report) => {
      const policies = [...new Set(report.recommended_actions.map((action) => action.policy))];
      const expanded = state.expandedReportId === report.report_id;
      const detail = state.reportDetails[report.report_id];
      return `
        <article class="item">
          <div class="item-head">
            <div>
              <div class="item-title">${escapeHtml(report.summary.symptom)}</div>
              <div class="item-meta">
                <span>${escapeHtml(report.report_id)}</span>
                <span>${escapeHtml(report.cluster_id)}</span>
                <span>${formatDate(report.created_at)}</span>
              </div>
            </div>
            <span class="badge ${confidenceClass(report.summary.confidence)}">${escapeHtml(report.summary.confidence)}</span>
          </div>
          <div class="item-meta">
            <span>${escapeHtml(report.summary.most_likely_cause)}</span>
          </div>
          <div class="item-actions">
            ${policies.map((policy) => `<span class="badge ${policyClass(policy)}">${escapeHtml(policy)}</span>`).join("")}
            <button type="button" data-report-detail="${escapeHtml(report.report_id)}">${expanded ? "Hide details" : "Details"}</button>
            <button type="button" data-report-json="${escapeHtml(report.report_id)}">Copy JSON</button>
          </div>
          <div class="detail-slot">${expanded ? renderReportDetail(detail) : ""}</div>
        </article>
      `;
    })
    .join("");

  target.querySelectorAll("[data-report-detail]").forEach((button) => {
    button.addEventListener("click", () => toggleReportDetail(button.dataset.reportDetail));
  });
  target.querySelectorAll("[data-report-json]").forEach((button) => {
    button.addEventListener("click", () => copyReportJson(button.dataset.reportJson));
  });
}

async function toggleReportDetail(reportId) {
  if (state.expandedReportId === reportId) {
    state.expandedReportId = "";
    renderReports();
    return;
  }

  state.expandedReportId = reportId;
  if (!state.reportDetails[reportId]) {
    state.reportDetails[reportId] = { loading: true };
    renderReports();
    try {
      state.reportDetails[reportId] = await api(`/api/rca/reports/${encodeURIComponent(reportId)}`, {
        headers: authHeaders({ allowAdminFallback: true }),
      });
    } catch (error) {
      state.reportDetails[reportId] = { error: error.message };
      toast(error.message);
    }
  }
  renderReports();
}

function renderReportDetail(report) {
  if (!report || report.loading) {
    return `<div class="report-detail empty">Loading report detail.</div>`;
  }
  if (report.error) {
    return `<div class="report-detail empty">${escapeHtml(report.error)}</div>`;
  }

  const signals = asArray(reportSection(report, "derived_signals").signals);
  const checklist = asArray(reportSection(report, "resolution_checklist").items);
  const llmAnalysis = reportSection(report, "llm_analysis").analysis || {};

  return `
    <div class="report-detail">
      <div class="detail-grid">
        <div>
          <h3>Scope</h3>
          <dl class="detail-list">
            <div><dt>Report</dt><dd>${escapeHtml(report.report_id)}</dd></div>
            <div><dt>Cluster</dt><dd>${escapeHtml(report.cluster_id)}</dd></div>
            <div><dt>Nodes</dt><dd>${escapeHtml(formatList(report.scope?.nodes))}</dd></div>
            <div><dt>Components</dt><dd>${escapeHtml(formatList(report.scope?.components))}</dd></div>
          </dl>
        </div>
        <div>
          <h3>LLM</h3>
          <dl class="detail-list">
            <div><dt>Status</dt><dd>${escapeHtml(llmAnalysis.status || "unknown")}</dd></div>
            <div><dt>Provider</dt><dd>${escapeHtml(llmAnalysis.provider || "n/a")}</dd></div>
            <div><dt>Model</dt><dd>${escapeHtml(llmAnalysis.model || "n/a")}</dd></div>
            <div><dt>Reason</dt><dd>${escapeHtml(llmAnalysis.reason || llmAnalysis.error || "n/a")}</dd></div>
          </dl>
        </div>
      </div>
      <div class="detail-block">
        <h3>Root Cause Candidates</h3>
        ${renderRootCauseCandidates(report.root_cause_candidates)}
      </div>
      <div class="detail-block">
        <h3>Recommended Actions</h3>
        ${renderRecommendedActions(report.recommended_actions)}
      </div>
      <div class="detail-block">
        <h3>Derived Signals</h3>
        ${renderSignals(signals)}
      </div>
      <div class="detail-block">
        <h3>Resolution Checklist</h3>
        ${renderChecklist(checklist)}
      </div>
    </div>
  `;
}

function renderRootCauseCandidates(candidates) {
  const items = asArray(candidates);
  if (!items.length) return `<p class="detail-empty">No root cause candidates.</p>`;
  return `
    <ol class="detail-rows">
      ${items
        .map(
          (candidate) => `
            <li>
              <div class="detail-row-head">
                <strong>${escapeHtml(candidate.cause)}</strong>
                <span class="badge ${confidenceClass(candidate.confidence)}">${escapeHtml(candidate.confidence)}</span>
              </div>
              <p>${escapeHtml(formatList(candidate.supporting_evidence))}</p>
            </li>
          `,
        )
        .join("")}
    </ol>
  `;
}

function renderRecommendedActions(actions) {
  const items = asArray(actions);
  if (!items.length) return `<p class="detail-empty">No recommended actions.</p>`;
  return `
    <div class="detail-rows">
      ${items
        .map(
          (action) => `
            <div>
              <div class="detail-row-head">
                <strong>${escapeHtml(action.action)}</strong>
                <span class="badge ${policyClass(action.policy)}">${escapeHtml(action.policy)}</span>
              </div>
              <p>${escapeHtml(action.reason)}</p>
              <p>${escapeHtml(action.automation_mode || "manual")} / automation allowed: ${escapeHtml(action.automation_allowed)}</p>
            </div>
          `,
        )
        .join("")}
    </div>
  `;
}

function renderSignals(signals) {
  if (!signals.length) return `<p class="detail-empty">No derived signals.</p>`;
  return `
    <div class="detail-rows">
      ${signals
        .map(
          (signal) => `
            <div>
              <div class="detail-row-head">
                <strong>${escapeHtml(signal.signal)}</strong>
                <span class="badge ${signal.severity === "critical" ? "red" : "amber"}">${escapeHtml(signal.severity)}</span>
              </div>
              <p>${escapeHtml(signal.component)}: ${escapeHtml(signal.interpretation)}</p>
              <p>${escapeHtml(signal.next_step)}</p>
            </div>
          `,
        )
        .join("")}
    </div>
  `;
}

function renderChecklist(items) {
  if (!items.length) return `<p class="detail-empty">No checklist items.</p>`;
  return `
    <div class="detail-rows">
      ${items
        .map(
          (item) => `
            <div>
              <div class="detail-row-head">
                <strong>${escapeHtml(item.component)}</strong>
                <span>${escapeHtml(item.check)}</span>
              </div>
              <code>${escapeHtml(item.command)}</code>
            </div>
          `,
        )
        .join("")}
    </div>
  `;
}

async function copyReportJson(reportId) {
  try {
    const report = await api(`/api/rca/reports/${encodeURIComponent(reportId)}`, {
      headers: authHeaders({ allowAdminFallback: true }),
    });
    await navigator.clipboard.writeText(JSON.stringify(report, null, 2));
    toast("Report JSON copied.");
  } catch (error) {
    toast(error.message);
  }
}

async function copyWebhookEndpoint() {
  const value = $("#webhookEndpoint").textContent;
  try {
    await navigator.clipboard.writeText(value);
    toast("Webhook endpoint copied.");
  } catch {
    toast(value);
  }
}

async function api(path, options = {}) {
  let response;
  try {
    response = await fetch(path, {
      cache: "no-store",
      credentials: "same-origin",
      ...options,
      headers: {
        "Content-Type": "application/json",
        ...(options.headers || {}),
      },
    });
  } catch {
    throw new Error("Backend is unreachable. Check the server or network path.");
  }

  const contentType = response.headers.get("content-type") || "";
  const rawBody = await response.text();
  const body = parseResponseBody(rawBody, contentType);
  if (!response.ok) {
    const message = errorMessage(body, response.statusText);
    if (response.status === 401 && state.sessionToken && message.toLowerCase().includes("session")) {
      state.sessionToken = "";
      state.currentUser = null;
      sessionStorage.removeItem("rca_session_token");
      renderSession();
    }
    throw new Error(message);
  }
  return body;
}

function renderSession() {
  const target = $("#sessionStatus");
  if (!state.currentUser) {
    target.textContent = state.sessionToken ? "Session expired" : "Not signed in";
    return;
  }
  target.textContent = `${state.currentUser.email} / ${state.currentUser.role}`;
}

function authHeaders(options = {}) {
  const headers = {};
  if (state.sessionToken) headers.Authorization = `Bearer ${state.sessionToken}`;
  if (options.allowAdminFallback && state.adminToken) headers["X-Admin-Token"] = state.adminToken;
  return headers;
}

function ensureAuth() {
  if (state.sessionToken || state.adminToken) return true;
  toast("Sign in or enter bootstrap admin token.");
  return false;
}

function formPayload(form) {
  const data = new FormData(form);
  return Object.fromEntries(
    [...data.entries()].map(([key, value]) => {
      const normalized = typeof value === "string" ? value.trim() : value;
      return [key, normalized === "" ? null : normalized];
    }),
  );
}

function renderError(selector, message) {
  const target = $(selector);
  target.className = "list empty";
  target.textContent = message;
}

function parseResponseBody(rawBody, contentType) {
  if (!rawBody) return null;
  if (!contentType.includes("application/json")) return rawBody;
  try {
    return JSON.parse(rawBody);
  } catch {
    return rawBody;
  }
}

function errorMessage(body, fallback) {
  const detail = typeof body === "object" && body !== null ? body.detail : body;
  if (Array.isArray(detail)) {
    return detail.map((item) => item.msg || String(item)).join(", ");
  }
  if (typeof detail === "object" && detail !== null) {
    return JSON.stringify(detail);
  }
  return detail || fallback || "Request failed.";
}

function reportSection(report, sectionType) {
  return asArray(report.evidence).find((section) => section.type === sectionType) || {};
}

function asArray(value) {
  return Array.isArray(value) ? value : [];
}

function formatList(value) {
  if (Array.isArray(value)) return value.length ? value.join(", ") : "n/a";
  if (value === undefined || value === null || value === "") return "n/a";
  return String(value);
}

function setActiveNav(activeLink) {
  document.querySelectorAll(".nav-link").forEach((link) => link.classList.remove("active"));
  activeLink.classList.add("active");
}

function toast(message) {
  const target = $("#toast");
  target.textContent = message;
  target.classList.add("visible");
  window.clearTimeout(target.dataset.timer);
  target.dataset.timer = window.setTimeout(() => target.classList.remove("visible"), 3200);
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

function confidenceClass(confidence) {
  if (confidence === "high") return "green";
  if (confidence === "medium") return "amber";
  return "red";
}

function policyClass(policy) {
  if (policy === "AUTO_SAFE") return "green";
  if (policy === "NEVER_AUTO_EXECUTE") return "red";
  if (policy === "APPROVAL_REQUIRED" || policy === "GITOPS_PR_ONLY") return "amber";
  return "";
}

function escapeHtml(value) {
  return String(value ?? "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#039;");
}
