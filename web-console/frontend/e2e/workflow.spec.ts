import { expect, test, type APIRequestContext, type Page, type Route } from "@playwright/test";

const username = process.env.CONSOLE_USERNAME || "e2e-admin";
const password = process.env.CONSOLE_PASSWORD || "e2e-password-123";
const origin = (process.env.CONSOLE_BASE_URL || "http://127.0.0.1:18087").replace(/\/$/, "");

interface ClusterResponse {
  cluster_id: string;
  name: string;
}

interface AnalysisTaskResponse {
  task_id: string;
  status: string;
}

interface ReportResponse {
  report_id: string;
  cluster_id: string;
  recommended_actions?: Array<{
    policy?: string;
    source?: string;
  }>;
}

function authHeaders(token: string) {
  return { Authorization: `Bearer ${token}`, Origin: origin };
}

async function login(page: Page, path = "/overview"): Promise<string> {
  await page.goto(path);
  await expect(page.getByTestId("login-username")).toBeVisible();
  await page.getByTestId("login-username").fill(username);
  await page.getByTestId("login-password").fill(password);
  const responsePromise = page.waitForResponse((response) =>
    response.url().endsWith("/api/auth/login") && response.request().method() === "POST",
  );
  await page.getByTestId("login-submit").click();
  const response = await responsePromise;
  expect(response.ok()).toBeTruthy();
  const session = await response.json() as { access_token?: string };
  await expect(page.locator(".console-shell")).toBeVisible();
  expect(session.access_token).toBeTruthy();
  return session.access_token!;
}

async function createCluster(request: APIRequestContext, name: string, token: string): Promise<ClusterResponse> {
  const response = await request.post("/api/clusters", {
    data: { name, environment: "e2e", description: "Playwright workflow fixture" },
    headers: authHeaders(token),
  });
  expect(response.status()).toBe(201);
  return response.json();
}

async function deleteCluster(request: APIRequestContext, cluster: ClusterResponse | null, token: string) {
  if (!cluster) return;
  const response = await request.delete(`/api/clusters/${encodeURIComponent(cluster.cluster_id)}`, {
    params: { confirm_name: cluster.name },
    headers: authHeaders(token),
  });
  expect([200, 404]).toContain(response.status());
}

async function waitForReport(request: APIRequestContext, clusterId: string, token: string): Promise<ReportResponse> {
  let report: ReportResponse | undefined;
  await expect.poll(async () => {
    const response = await request.get("/api/rca/reports", { headers: authHeaders(token) });
    expect(response.ok()).toBeTruthy();
    const reports = await response.json() as ReportResponse[];
    report = reports.find((item) => item.cluster_id === clusterId);
    return report?.report_id || "";
  }, { timeout: 45_000, intervals: [250, 500, 1_000] }).not.toBe("");
  return report!;
}

test("preserves a protected detail URL through login and session expiry", async ({ page }) => {
  const protectedPath = "/reports/report-e2e-missing";
  await page.goto(protectedPath);
  await page.getByTestId("login-username").fill(username);
  await page.getByTestId("login-password").fill("wrong-password");
  await page.getByTestId("login-submit").click();
  await expect(page.locator(".login-screen")).toBeVisible();
  await expect(page.locator(".console-toast")).toContainText(/Invalid username or password/i);

  await page.getByTestId("login-password").fill(password);
  const loginResponsePromise = page.waitForResponse((response) =>
    response.url().endsWith("/api/auth/login") && response.request().method() === "POST",
  );
  await page.getByTestId("login-submit").click();
  const loginResponse = await loginResponsePromise;
  const session = await loginResponse.json() as { access_token: string };
  await expect(page).toHaveURL(new RegExp(`${protectedPath}$`));
  await expect(page.getByTestId("route-not-found")).toBeVisible();

  const logout = await page.request.post("/api/auth/logout", { headers: authHeaders(session.access_token) });
  expect(logout.ok()).toBeTruthy();
  await page.reload();
  await expect(page.locator(".login-screen")).toBeVisible();
  await expect(page).toHaveURL(new RegExp(`${protectedPath}$`));
});

test("registers a cluster, shows its install command, restores detail, and deletes it", async ({ page }) => {
  const clusterName = `e2e-cluster-${Date.now()}`;
  let cluster: ClusterResponse | null = null;
  const token = await login(page, "/clusters");

  try {
    await page.getByTestId("cluster-name").fill(clusterName);
    await page.getByTestId("cluster-environment").selectOption("stage");
    await page.getByTestId("cluster-description").fill("Created by the Playwright onboarding workflow");

    const createResponsePromise = page.waitForResponse((response) =>
      response.url().endsWith("/api/clusters") && response.request().method() === "POST",
    );
    await page.getByTestId("cluster-create").click();
    const createResponse = await createResponsePromise;
    expect(createResponse.status()).toBe(201);
    cluster = await createResponse.json() as ClusterResponse;

    await expect(page).toHaveURL(new RegExp(`/clusters/${cluster.cluster_id}$`));
    await expect(page.getByTestId("install-command")).toBeVisible();
    await expect(page.getByTestId("install-command")).toContainText(/helm upgrade|kubectl/i);

    await page.reload();
    await expect(page.getByTestId("cluster-row").filter({ hasText: clusterName })).toBeVisible();
    await expect(page.getByRole("heading", { name: clusterName, exact: true })).toBeVisible();

    await page.getByRole("button", { name: /Agent enrollment/ }).click();
    await expect(page.getByLabel("Enrollment mode")).toHaveValue("bootstrap_token");
    const enrollmentResponsePromise = page.waitForResponse((response) =>
      response.url().includes(`/api/clusters/${cluster!.cluster_id}/agent-enrollment`)
        && response.request().method() === "PUT",
    );
    await page.getByRole("button", { name: "Save enrollment" }).click();
    expect((await enrollmentResponsePromise).ok()).toBeTruthy();
    await expect(page.getByText("Agent enrollment updated.")).toBeVisible();

    const row = page.getByTestId("cluster-row").filter({ hasText: clusterName });
    await row.getByTestId("cluster-delete").click();
    await expect(page.getByTestId("delete-cluster-dialog")).toBeVisible();
    await page.getByTestId("delete-cluster-confirm-name").fill(clusterName);
    const deleteResponsePromise = page.waitForResponse((response) =>
      response.url().includes(`/api/clusters/${cluster!.cluster_id}`) && response.request().method() === "DELETE",
    );
    await page.getByTestId("delete-cluster-confirm").click();
    expect((await deleteResponsePromise).ok()).toBeTruthy();
    await expect(page).toHaveURL(/\/clusters$/);
    await expect(page.getByTestId("cluster-row").filter({ hasText: clusterName })).toHaveCount(0);
    cluster = null;
  } finally {
    await deleteCluster(page.request, cluster, token);
  }
});

test("runs RCA evidence and records approval, manual completion, and rejection", async ({ page }) => {
  let cluster: ClusterResponse | null = null;
  const token = await login(page);

  try {
    cluster = await createCluster(page.request, `e2e-rca-${Date.now()}`, token);
    const demoResponse = await page.request.post("/api/demo/scenarios/disk-pressure/run", {
      data: { confirmed: true, cluster_id: cluster.cluster_id, node_name: "e2e-worker-01" },
      headers: authHeaders(token),
    });
    expect(demoResponse.status()).toBe(202);
    const demo = await demoResponse.json() as { analysis_task: AnalysisTaskResponse };

    await expect.poll(async () => {
      const response = await page.request.get(`/api/rca/analysis-tasks/${demo.analysis_task.task_id}`, { headers: authHeaders(token) });
      const task = await response.json() as AnalysisTaskResponse;
      return task.status;
    }, { timeout: 45_000, intervals: [250, 500, 1_000] }).toBe("completed");

    const report = await waitForReport(page.request, cluster.cluster_id, token);
    const actionIndex = (report.recommended_actions || []).findIndex((action) =>
      action.policy === "APPROVAL_REQUIRED" && action.source !== "llm",
    );
    expect(actionIndex).toBeGreaterThanOrEqual(0);

    await page.goto(`/reports/${report.report_id}`);
    await expect(page.getByTestId("view-reports")).toBeVisible();
    await expect(page.locator(".report-detail")).toContainText(report.report_id);

    const actionCard = page.getByTestId("recommended-action").nth(actionIndex);
    await actionCard.getByTestId("request-action").click();
    await page.getByTestId("action-request-note").fill("E2E approval workflow request");
    await page.getByTestId("action-request-confirm").click();

    let pending = page.getByTestId("action-request").filter({ hasText: "pending_approval" }).first();
    await expect(pending).toBeVisible();
    await pending.getByTestId("action-decision-note").fill("Approved for a human-run runbook");
    await pending.getByTestId("action-approve").click();

    let approved = page.getByTestId("action-request").filter({ hasText: "approved_manual" }).first();
    await expect(approved).toBeVisible();
    await approved.getByTestId("action-decision-note").fill("Runbook completed outside the platform");
    await approved.getByTestId("action-complete-manual").click();
    await expect(page.getByTestId("action-request").filter({ hasText: "completed" }).first()).toBeVisible();

    await actionCard.getByTestId("request-action").click();
    await page.getByTestId("action-request-note").fill("E2E rejection workflow request");
    await page.getByTestId("action-request-confirm").click();
    pending = page.getByTestId("action-request").filter({ hasText: "pending_approval" }).first();
    await expect(pending).toBeVisible();
    await pending.getByTestId("action-decision-note").fill("Rejected by the E2E reviewer");
    await pending.getByTestId("action-reject").click();
    await expect(page.getByTestId("action-request").filter({ hasText: "rejected" }).first()).toBeVisible();
  } finally {
    await deleteCluster(page.request, cluster, token);
  }
});

test("applies viewer navigation and mutation restrictions in the console", async ({ page }) => {
  const asViewer = async (route: Route) => {
    const response = await route.fetch();
    if (!response.ok()) {
      await route.fulfill({ response });
      return;
    }
    const body = await response.json() as { user?: Record<string, unknown>; role?: string };
    const json = body.user
      ? { ...body, user: { ...body.user, role: "viewer" } }
      : { ...body, role: "viewer" };
    await route.fulfill({
      response,
      json,
    });
  };
  await page.route("**/api/auth/login", asViewer);
  await page.route("**/api/auth/me", asViewer);

  const token = await login(page, "/audit");
  let cluster: ClusterResponse | null = null;
  try {
    await expect(page).toHaveURL(/\/overview$/);
    await expect(page.getByTestId("nav-audit")).toHaveCount(0);

    cluster = await createCluster(page.request, `e2e-viewer-${Date.now()}`, token);
    await page.goto(`/clusters/${cluster.cluster_id}`);
    const row = page.getByTestId("cluster-row").filter({ hasText: cluster.name });
    await expect(row).toBeVisible();
    await expect(page.getByTestId("cluster-name")).toBeDisabled();
    await expect(page.getByTestId("cluster-create")).toBeDisabled();
    await expect(row.getByTestId("cluster-delete")).toHaveCount(0);
    await expect(row.getByTestId("cluster-install-command")).toHaveCount(0);

    await page.getByTestId("nav-reports").click();
    await expect(page.getByRole("button", { name: "Export all" })).toHaveCount(0);
  } finally {
    await deleteCluster(page.request, cluster, token);
  }
});

test("keeps stale data visible and exposes structured API failure context", async ({ page }) => {
  let reportRequests = 0;
  let failReportRequests = false;
  await page.route("**/api/v1/rca/reports*", async (route) => {
    if (route.request().method() !== "GET") {
      await route.continue();
      return;
    }
    reportRequests += 1;
    if (failReportRequests) {
      await route.fulfill({
        status: 503,
        contentType: "application/json",
        body: JSON.stringify({
          code: "report_store_unavailable",
          title: "Report store unavailable",
          detail: "The report query is temporarily unavailable.",
          trace_id: "trace-e2e-reports",
        }),
      });
      return;
    }
    await route.continue();
  });

  await login(page, "/reports");
  const reportList = page.getByTestId("report-list");
  await expect(reportList).toHaveAttribute("data-loaded", "true");
  await expect(reportList).toHaveAttribute("aria-busy", "false");
  const requestsBeforeFailure = reportRequests;
  failReportRequests = true;
  await page.getByRole("button", { name: "Refresh", exact: true }).click();

  const failure = page.getByTestId("data-status-failure-reports");
  await expect(failure).toBeVisible();
  await expect(failure).toContainText("The report query is temporarily unavailable.");
  await expect(failure).toContainText("HTTP 503");
  await expect(failure).toContainText("report_store_unavailable");
  await expect(failure).toContainText("trace-e2e-reports");
  await expect(failure).toContainText("Showing last successful data");

  failReportRequests = false;
  await page.getByTestId("data-status-retry").click();
  await expect(page.getByTestId("data-status-failure-reports")).toHaveCount(0);
  expect(reportRequests).toBeGreaterThanOrEqual(requestsBeforeFailure + 2);
});

test("shows agent connection states and filters them without mobile overflow", async ({ page }) => {
  const healthFixture = [
    agentHealthFixture("worker-ok", "healthy", 8),
    agentHealthFixture("worker-stale", "stale", 420),
    agentHealthFixture("worker-collector", "collector_degraded", 30, "Kernel collector unavailable"),
    agentHealthFixture("worker-version", "version_mismatch", 18, "Agent protocol is outside supported range"),
    agentHealthFixture("worker-auth", "unauthorized", 10, "Agent authentication failed"),
    agentHealthFixture("worker-offline", "offline", 7_500),
  ];
  await page.route("**/api/v1/agent-health*", (route) => route.fulfill({
    status: 200,
    contentType: "application/json",
    body: JSON.stringify(healthFixture),
  }));
  await page.setViewportSize({ width: 390, height: 844 });
  await login(page, "/clusters");

  const panel = page.getByTestId("agent-fleet-panel");
  await expect(panel).toBeVisible();
  await expect(panel.getByTestId("agent-fleet-row")).toHaveCount(6);
  await panel.getByTestId("agent-status-filter-unauthorized").click();
  await expect(panel.getByTestId("agent-fleet-row")).toHaveCount(1);
  await expect(panel).toContainText("worker-auth");
  await expect(panel).toContainText("Agent authentication failed");

  const dimensions = await page.evaluate(() => ({
    documentWidth: document.documentElement.scrollWidth,
    viewportWidth: document.documentElement.clientWidth,
  }));
  expect(dimensions.documentWidth).toBeLessThanOrEqual(dimensions.viewportWidth + 1);
});

test("keeps the cluster confirmation workflow usable on mobile with a keyboard", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  const token = await login(page);
  let cluster: ClusterResponse | null = null;

  try {
    cluster = await createCluster(page.request, `e2e-mobile-${Date.now()}`, token);
    await page.goto(`/clusters/${cluster.cluster_id}`);
    const row = page.getByTestId("cluster-row").filter({ hasText: cluster.name });
    await expect(row).toBeVisible();

    const dimensions = await page.evaluate(() => ({
      documentWidth: document.documentElement.scrollWidth,
      viewportWidth: document.documentElement.clientWidth,
    }));
    expect(dimensions.documentWidth).toBeLessThanOrEqual(dimensions.viewportWidth + 1);

    const deleteButton = row.getByTestId("cluster-delete");
    await deleteButton.focus();
    await page.keyboard.press("Enter");
    await expect(page.getByTestId("delete-cluster-dialog")).toBeVisible();

    await page.getByTestId("delete-cluster-confirm-name").focus();
    await page.keyboard.type(cluster.name);
    await page.getByTestId("delete-cluster-confirm").focus();
    await page.keyboard.press("Enter");
    await expect(page).toHaveURL(/\/clusters$/);
    await expect(page.getByTestId("cluster-row").filter({ hasText: cluster.name })).toHaveCount(0);
    cluster = null;
  } finally {
    await deleteCluster(page.request, cluster, token);
  }
});

function agentHealthFixture(nodeName: string, healthStatus: string, heartbeatAgeSeconds: number, reason = "") {
  return {
    agent_id: `agent-${nodeName}`,
    cluster_id: "cluster-e2e-health",
    node_name: nodeName,
    agent_version: "0.1.0",
    agent_protocol_version: "1",
    platform_protocol_version: "1",
    health_status: healthStatus,
    reported_status: healthStatus === "healthy" ? "healthy" : "degraded",
    supported_collectors: ["disk", "kernel", "runtime"],
    heartbeat_age_seconds: heartbeatAgeSeconds,
    last_heartbeat_at: new Date(Date.now() - heartbeatAgeSeconds * 1000).toISOString(),
    reasons: reason ? [reason] : [],
  };
}
