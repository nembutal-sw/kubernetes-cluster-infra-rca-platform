export type ConsoleView =
  | "overview"
  | "clusters"
  | "reports"
  | "incidents"
  | "pipeline"
  | "audit"
  | "webhooks"
  | "settings";

export interface ConsoleRoute {
  view: ConsoleView;
  valid: boolean;
  canonicalPath: string;
  clusterId?: string;
  reportId?: string;
  incidentId?: string;
}

const VIEWS = new Set<ConsoleView>([
  "overview",
  "clusters",
  "reports",
  "incidents",
  "pipeline",
  "audit",
  "webhooks",
  "settings",
]);

const DETAIL_VIEWS = new Set<ConsoleView>(["clusters", "reports", "incidents"]);

export function parseConsoleRoute(pathname: string): ConsoleRoute {
  const normalized = normalizePath(pathname);
  if (normalized === "/" || normalized === "/console") {
    return { view: "overview", valid: true, canonicalPath: "/overview" };
  }

  const segments = normalized.split("/").filter(Boolean);
  const view = segments[0] as ConsoleView;
  if (!VIEWS.has(view)) return invalidRoute();
  if (segments.length === 1) {
    return { view, valid: true, canonicalPath: pathForView(view) };
  }
  if (segments.length !== 2 || !DETAIL_VIEWS.has(view)) return invalidRoute();

  const resourceId = safeDecode(segments[1]);
  if (!resourceId) return invalidRoute();
  if (view === "clusters") {
    return { view, valid: true, canonicalPath: clusterPath(resourceId), clusterId: resourceId };
  }
  if (view === "reports") {
    return { view, valid: true, canonicalPath: reportPath(resourceId), reportId: resourceId };
  }
  return { view, valid: true, canonicalPath: incidentPath(resourceId), incidentId: resourceId };
}

export function pathForView(view: string): string {
  return VIEWS.has(view as ConsoleView) ? `/${view}` : "/overview";
}

export function clusterPath(clusterId: string): string {
  return `/clusters/${encodeURIComponent(clusterId)}`;
}

export function reportPath(reportId: string): string {
  return `/reports/${encodeURIComponent(reportId)}`;
}

export function incidentPath(incidentId: string): string {
  return `/incidents/${encodeURIComponent(incidentId)}`;
}

function normalizePath(pathname: string): string {
  const value = pathname.trim() || "/";
  if (value === "/") return value;
  return value.replace(/\/+$/, "") || "/";
}

function safeDecode(value: string): string {
  try {
    return decodeURIComponent(value).trim();
  } catch {
    return "";
  }
}

function invalidRoute(): ConsoleRoute {
  return { view: "overview", valid: false, canonicalPath: "/overview" };
}
