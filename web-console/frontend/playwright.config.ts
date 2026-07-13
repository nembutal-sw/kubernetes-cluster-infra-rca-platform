import { defineConfig } from "@playwright/test";

const baseURL = (process.env.CONSOLE_BASE_URL || "http://127.0.0.1:18087").replace(/\/$/, "");
const serverPort = new URL(baseURL).port || "80";
const serverCommand = process.env.CONSOLE_E2E_SERVER_COMMAND
  || "java -jar ../target/cluster-infra-rca-platform-0.1.0.jar";

export default defineConfig({
  testDir: "./e2e",
  fullyParallel: false,
  workers: 1,
  timeout: 60_000,
  expect: { timeout: 12_000 },
  reporter: [
    ["list"],
    ["html", { outputFolder: "playwright-report", open: "never" }],
  ],
  outputDir: "test-results",
  use: {
    baseURL,
    headless: process.env.PLAYWRIGHT_HEADLESS !== "false",
    channel: process.env.PLAYWRIGHT_CHANNEL || undefined,
    screenshot: "only-on-failure",
    trace: "retain-on-failure",
    video: process.env.PLAYWRIGHT_VIDEO === "false" ? "off" : "retain-on-failure",
    viewport: { width: 1440, height: 1000 },
  },
  webServer: {
    command: serverCommand,
    cwd: ".",
    url: `${baseURL}/health/ready`,
    reuseExistingServer: process.env.CONSOLE_E2E_REUSE_SERVER === "true",
    timeout: 180_000,
    stdout: "pipe",
    stderr: "pipe",
    env: {
      SERVER_PORT: serverPort,
      RCA_JDBC_URL: "jdbc:h2:mem:rca_e2e;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
      RCA_DB_USERNAME: "sa",
      RCA_DB_PASSWORD: "",
      RCA_DEFAULT_ADMIN_USERNAME: process.env.CONSOLE_USERNAME || "e2e-admin",
      RCA_DEFAULT_ADMIN_PASSWORD: process.env.CONSOLE_PASSWORD || "e2e-password-123",
      RCA_WEBHOOK_TOKEN: "e2e-webhook-token",
      RCA_DEMO_ENABLED: "true",
      RCA_PIPELINE_ENABLED: "true",
      RCA_PIPELINE_INITIAL_DELAY_MS: "100",
      RCA_PIPELINE_POLL_INTERVAL_MS: "250",
      RCA_OBSERVABILITY_ENABLED: "false",
      RCA_MAINTENANCE_ENABLED: "false",
      RCA_MONITORING_ENABLED: "false",
    },
  },
});
