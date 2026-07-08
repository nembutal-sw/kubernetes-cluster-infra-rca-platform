import { chromium } from "@playwright/test";

const baseUrl = (process.env.CONSOLE_BASE_URL || "http://127.0.0.1:8080").replace(/\/$/, "");
const username = process.env.CONSOLE_USERNAME || "admin";
const password = process.env.CONSOLE_PASSWORD || "admin";
const channel = process.env.PLAYWRIGHT_CHANNEL || "";
const headless = process.env.PLAYWRIGHT_HEADLESS !== "false";

const routes = ["overview", "clusters", "reports", "incidents", "pipeline", "audit", "webhooks", "settings"];
const locales = [
  { id: "en", expected: "Settings" },
  { id: "ko", expected: "설정" },
];
const viewports = [
  { name: "desktop", viewport: { width: 1440, height: 1000 } },
  { name: "mobile", viewport: { width: 390, height: 844 }, isMobile: true },
];

const failures = [];

function fail(scope, message) {
  failures.push(`[${scope}] ${message}`);
}

function usefulConsoleMessage(message, locationUrl = "") {
  const combined = `${message} ${locationUrl}`;
  if (combined.includes("/api/auth/me") && combined.includes("401")) return false;
  if (message.includes("status of 401")) return false;
  if (combined.includes("favicon") && combined.includes("404")) return false;
  return true;
}

async function loginIfNeeded(page, scope) {
  await page.goto(baseUrl, { waitUntil: "domcontentloaded" });
  await page.waitForFunction(() => document.body.innerText.trim().length > 0, null, { timeout: 10000 });

  if (await page.locator(".login-screen").count()) {
    await page.locator('input[autocomplete="username"]').fill(username);
    await page.locator('input[autocomplete="current-password"]').fill(password);
    await page.getByRole("button", { name: /Sign in|로그인/ }).click();
  }

  try {
    await page.locator(".console-shell").waitFor({ state: "visible", timeout: 10000 });
  } catch (error) {
    fail(scope, `console shell did not render after login: ${error.message}`);
  }
}

async function assertRoute(page, route, scope, viewport) {
  const nav = page.getByTestId(`nav-${route}`);
  if ((await nav.count()) !== 1) {
    fail(scope, `missing nav target nav-${route}`);
    return;
  }

  await nav.click();
  const view = page.getByTestId(`view-${route}`);
  try {
    await view.waitFor({ state: "visible", timeout: 7000 });
  } catch (error) {
    fail(scope, `view-${route} did not render: ${error.message}`);
    return;
  }

  const state = await page.evaluate(() => {
    const bodyText = document.body.innerText.trim();
    const root = document.querySelector("#rca-console-root");
    return {
      bodyLength: bodyText.length,
      mainLength: document.querySelector("main")?.innerText.trim().length || 0,
      rootChildren: root?.children.length || 0,
      documentWidth: document.documentElement.scrollWidth,
      viewportWidth: document.documentElement.clientWidth,
      lang: document.documentElement.lang,
    };
  });

  if (state.rootChildren === 0 || state.bodyLength < 20 || state.mainLength < 10) {
    fail(scope, `route ${route} appears blank: ${JSON.stringify(state)}`);
  }
  if (viewport.isMobile && state.documentWidth > state.viewportWidth + 24) {
    fail(scope, `route ${route} has horizontal overflow: ${state.documentWidth}px > ${state.viewportWidth}px`);
  }
}

async function runScope(browser, viewport, locale) {
  const scope = `${viewport.name}/${locale.id}`;
  const context = await browser.newContext({
    viewport: viewport.viewport,
    isMobile: Boolean(viewport.isMobile),
  });
  await context.addInitScript(
    ({ localeId }) => {
      localStorage.setItem("rca_console_language", localeId);
    },
    { localeId: locale.id },
  );

  const page = await context.newPage();
  const consoleErrors = [];
  const pageErrors = [];
  page.on("console", (message) => {
    if (message.type() === "error" && usefulConsoleMessage(message.text(), message.location().url || "")) {
      consoleErrors.push(message.text());
    }
  });
  page.on("pageerror", (error) => {
    pageErrors.push(error.message);
  });

  try {
    await loginIfNeeded(page, scope);
    const htmlLang = await page.evaluate(() => document.documentElement.lang);
    if (htmlLang !== locale.id) {
      fail(scope, `expected html lang ${locale.id}, got ${htmlLang}`);
    }

    for (const route of routes) {
      await assertRoute(page, route, scope, viewport);
    }

    const pageText = await page.locator("body").innerText();
    if (!pageText.includes(locale.expected)) {
      fail(scope, `expected locale marker '${locale.expected}' was not visible`);
    }
  } catch (error) {
    fail(scope, error.stack || error.message);
  } finally {
    if (pageErrors.length) {
      fail(scope, `page errors: ${pageErrors.join(" | ")}`);
    }
    if (consoleErrors.length) {
      fail(scope, `console errors: ${consoleErrors.join(" | ")}`);
    }
    await context.close();
  }
}

async function main() {
  const launchOptions = { headless };
  if (channel) launchOptions.channel = channel;

  let browser;
  try {
    browser = await chromium.launch(launchOptions);
  } catch (error) {
    const hint = channel
      ? `Could not launch Playwright channel '${channel}'.`
      : "Could not launch Playwright Chromium. Run `npx playwright install chromium` or set PLAYWRIGHT_CHANNEL=chrome/msedge.";
    throw new Error(`${hint}\n${error.message}`);
  }

  try {
    for (const viewport of viewports) {
      for (const locale of locales) {
        await runScope(browser, viewport, locale);
      }
    }
  } finally {
    await browser.close();
  }

  if (failures.length) {
    console.error(`Route smoke failed for ${baseUrl}`);
    for (const item of failures) console.error(`- ${item}`);
    process.exit(1);
  }

  console.log(`Route smoke passed for ${baseUrl}`);
}

main().catch((error) => {
  console.error(error.stack || error.message);
  process.exit(1);
});
