const state = {
  clusters: [],
  reports: [],
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
            <button type="button" data-report-json="${escapeHtml(report.report_id)}">Copy JSON</button>
          </div>
        </article>
      `;
    })
    .join("");

  target.querySelectorAll("[data-report-json]").forEach((button) => {
    button.addEventListener("click", () => copyReportJson(button.dataset.reportJson));
  });
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
  const response = await fetch(path, {
    cache: "no-store",
    credentials: "same-origin",
    ...options,
    headers: {
      "Content-Type": "application/json",
      ...(options.headers || {}),
    },
  });
  const contentType = response.headers.get("content-type") || "";
  const body = contentType.includes("application/json") ? await response.json() : await response.text();
  if (!response.ok) {
    const detail = typeof body === "object" && body !== null ? body.detail : body;
    throw new Error(Array.isArray(detail) ? detail.map((item) => item.msg).join(", ") : detail || response.statusText);
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
  if (state.sessionToken) return { Authorization: `Bearer ${state.sessionToken}` };
  if (options.allowAdminFallback && state.adminToken) return { "X-Admin-Token": state.adminToken };
  return {};
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
