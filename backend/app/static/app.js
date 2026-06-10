const state = {
  clusters: [],
  reports: [],
  pendingUsers: [],
  adminToken: localStorage.getItem("rca_admin_token") || "",
};

const $ = (selector) => document.querySelector(selector);

document.addEventListener("DOMContentLoaded", () => {
  $("#adminToken").value = state.adminToken;
  $("#webhookEndpoint").textContent = `${window.location.origin}/api/webhooks/alertmanager`;

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

  refreshAll();
});

async function refreshAll() {
  await Promise.allSettled([loadClusters(), loadReports(), loadPendingUsers({ silent: true })]);
  renderOverview();
}

function saveAdminToken() {
  state.adminToken = $("#adminToken").value.trim();
  localStorage.setItem("rca_admin_token", state.adminToken);
  toast("Admin token saved.");
  loadPendingUsers();
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
  const form = event.currentTarget;
  const payload = formPayload(form);
  try {
    const cluster = await api("/api/clusters", {
      method: "POST",
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
    state.clusters = await api("/api/clusters");
    renderClusters();
  } catch (error) {
    renderError("#clusterList", error.message);
  } finally {
    renderOverview();
  }
}

async function loadReports() {
  try {
    state.reports = await api("/api/rca/reports");
    renderReports();
  } catch (error) {
    renderError("#reportList", error.message);
  } finally {
    renderOverview();
  }
}

async function loadPendingUsers(options = {}) {
  if (!state.adminToken) {
    state.pendingUsers = [];
    renderPendingUsers("Admin token required.");
    renderOverview();
    return;
  }

  try {
    const query = new URLSearchParams({
      admin_token: state.adminToken,
      status: "pending_approval",
    });
    state.pendingUsers = await api(`/api/admin/users?${query.toString()}`);
    renderPendingUsers();
  } catch (error) {
    state.pendingUsers = [];
    renderPendingUsers(options.silent ? "Admin token required." : error.message);
    if (!options.silent) toast(error.message);
  } finally {
    renderOverview();
  }
}

async function approveUser(userId, decision) {
  if (!state.adminToken) {
    toast("Admin token required.");
    return;
  }
  const roleSelect = document.querySelector(`[data-role-for="${userId}"]`);
  const noteInput = document.querySelector(`[data-note-for="${userId}"]`);
  try {
    await api(`/api/admin/users/${userId}/approval`, {
      method: "POST",
      body: JSON.stringify({
        admin_token: state.adminToken,
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
  const backendUrl = window.location.origin;
  const query = new URLSearchParams({ backend_url: backendUrl });
  try {
    const response = await api(`/api/clusters/${clusterId}/install-command?${query.toString()}`);
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
            <a class="badge" href="/api/rca/reports/${encodeURIComponent(report.report_id)}" target="_blank" rel="noreferrer">JSON</a>
          </div>
        </article>
      `;
    })
    .join("");
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
    headers: {
      "Content-Type": "application/json",
      ...(options.headers || {}),
    },
    ...options,
  });
  const contentType = response.headers.get("content-type") || "";
  const body = contentType.includes("application/json") ? await response.json() : await response.text();
  if (!response.ok) {
    const detail = typeof body === "object" && body !== null ? body.detail : body;
    throw new Error(Array.isArray(detail) ? detail.map((item) => item.msg).join(", ") : detail || response.statusText);
  }
  return body;
}

function formPayload(form) {
  const data = new FormData(form);
  return Object.fromEntries([...data.entries()].map(([key, value]) => [key, value === "" ? null : value]));
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
