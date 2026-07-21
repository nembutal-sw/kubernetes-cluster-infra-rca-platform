import { useCallback, useRef, useState } from "react";
import type { Dispatch, SetStateAction } from "react";

import { ApiError, apiErrorDetails } from "../api/client";
import { sortByTime } from "../lib/consoleUtils";
import type {
  ActionRequestView,
  AgentHealthView,
  ApiCall,
  AuditEventView,
  CatalogOverrideDraft,
  ClusterView,
  ConsoleDataSource,
  ConsoleLoadStates,
  DemoScenarioView,
  LlmDiagnosticResponse,
  LlmSetupGuideResponse,
  LoadState,
  OperationalCatalogDetail,
  OverviewSummary,
  PlatformInfo,
  UserAccount,
} from "../types";

type SourceSetter<T> = Dispatch<SetStateAction<LoadState<T>>>;

const EMPTY_OVERVIEW: OverviewSummary = {
  cluster_count: 0,
  report_count: 0,
  open_incident_count: 0,
  reports_last_24_hours: 0,
  analysis_backlog_count: 0,
  analysis_queued_count: 0,
  analysis_processing_count: 0,
  analysis_retry_count: 0,
  analysis_dead_letter_count: 0,
  action_request_count: 0,
  pending_approval_count: 0,
  manual_action_count: 0,
  blocked_action_count: 0,
  agent_count: 0,
  healthy_agent_count: 0,
  stale_agent_count: 0,
  degraded_agent_count: 0,
  offline_agent_count: 0,
  recent_clusters: [],
  recent_reports: [],
  recent_incidents: [],
};

function hasAuditAccess(role?: string): boolean {
  return role === "admin" || role === "auditor";
}

function hasCatalogOverrideDraftAccess(role?: string): boolean {
  return ["admin", "operator", "approver", "auditor"].includes(String(role || ""));
}

function initialState<T>(data: T): LoadState<T> {
  return { data, loading: false, stale: false };
}

function expectArray<T>(value: unknown, source: string): T[] {
  if (!Array.isArray(value)) {
    throw ApiError.invalidResponse(`${source} API did not return an array.`);
  }
  return value as T[];
}

function expectObject<T>(value: unknown, source: string): T {
  if (value === null || typeof value !== "object" || Array.isArray(value)) {
    throw ApiError.invalidResponse(`${source} API did not return an object.`);
  }
  return value as T;
}

function expectDemoScenarios(value: unknown): DemoScenarioView[] {
  if (Array.isArray(value)) return value as DemoScenarioView[];
  if (value && typeof value === "object" && "scenarios" in value) {
    return expectArray<DemoScenarioView>((value as { scenarios?: unknown }).scenarios, "Demo scenarios");
  }
  throw ApiError.invalidResponse("Demo scenarios API did not return a scenarios array.");
}

async function loadSource<T>(
  setter: SourceSetter<T>,
  request: Promise<unknown>,
  transform: (value: unknown) => T,
): Promise<boolean> {
  setter((previous) => ({ ...previous, loading: true }));
  try {
    const data = transform(await request);
    setter({ data, loading: false, loadedAt: new Date().toISOString(), stale: false });
    return true;
  } catch (error) {
    setter((previous) => ({
      ...previous,
      loading: false,
      error: apiErrorDetails(error, "Failed to load console data."),
      stale: Boolean(previous.loadedAt),
    }));
    return false;
  }
}

function sourcesForView(view: string, role?: string): ConsoleDataSource[] {
  switch (view) {
    case "overview":
      return ["overviewSummary"];
    case "clusters":
      return ["clusters", "agentHealth"];
    case "reports":
      return ["clusters", "platformInfo"];
    case "incidents":
      return ["clusters"];
    case "pipeline":
      return ["overviewSummary", "clusters", "actionRequests", "demoScenarios"];
    case "audit":
      return hasAuditAccess(role) ? ["auditEvents"] : [];
    case "settings": {
      const sources: ConsoleDataSource[] = [
        "platformInfo",
        "catalogDetail",
        "llmDiagnostics",
        "llmSetupGuide",
      ];
      if (hasCatalogOverrideDraftAccess(role)) sources.push("catalogOverrideDrafts");
      if (hasAuditAccess(role)) sources.push("notificationHistory");
      return sources;
    }
    default:
      return [];
  }
}

export function useConsoleData(
  callApi: ApiCall,
  currentUser: UserAccount | null,
  activeView: string,
) {
  const inFlight = useRef(false);
  const [loadingData, setLoadingData] = useState(false);
  const [lastUpdatedAt, setLastUpdatedAt] = useState<string>();
  const [lastCompleteRefreshAt, setLastCompleteRefreshAt] = useState<string>();
  const [overviewSummaryState, setOverviewSummaryState] = useState(() => initialState(EMPTY_OVERVIEW));
  const [clustersState, setClustersState] = useState(() => initialState<ClusterView[]>([]));
  const [actionRequestsState, setActionRequestsState] = useState(() => initialState<ActionRequestView[]>([]));
  const [agentHealthState, setAgentHealthState] = useState(() => initialState<AgentHealthView[]>([]));
  const [auditEventsState, setAuditEventsState] = useState(() => initialState<AuditEventView[]>([]));
  const [notificationHistoryState, setNotificationHistoryState] = useState(() => initialState<AuditEventView[]>([]));
  const [demoScenariosState, setDemoScenariosState] = useState(() => initialState<DemoScenarioView[]>([]));
  const [platformInfoState, setPlatformInfoState] = useState(() => initialState<PlatformInfo | null>(null));
  const [catalogDetailState, setCatalogDetailState] = useState(() => initialState<OperationalCatalogDetail | null>(null));
  const [catalogOverrideDraftsState, setCatalogOverrideDraftsState] = useState(() => initialState<CatalogOverrideDraft[]>([]));
  const [llmDiagnosticsState, setLlmDiagnosticsState] = useState(() => initialState<LlmDiagnosticResponse | null>(null));
  const [llmSetupGuideState, setLlmSetupGuideState] = useState(() => initialState<LlmSetupGuideResponse | null>(null));

  const loadConsoleData = useCallback(async (silent = false) => {
    if (inFlight.current) return;
    inFlight.current = true;
    if (!silent) setLoadingData(true);

    try {
      const sources = new Set(sourcesForView(activeView, currentUser?.role));
      const loaders: Promise<boolean>[] = [];
      if (sources.has("overviewSummary")) {
        loaders.push(loadSource<OverviewSummary>(
          setOverviewSummaryState,
          callApi<OverviewSummary>("/api/v1/overview/summary"),
          (value) => expectObject<OverviewSummary>(value, "Overview summary"),
        ));
      }
      if (sources.has("clusters")) {
        loaders.push(loadSource(
          setClustersState,
          callApi<ClusterView[]>("/api/clusters"),
          (value) => expectArray<ClusterView>(value, "Clusters"),
        ));
      }
      if (sources.has("actionRequests")) {
        loaders.push(loadSource(
          setActionRequestsState,
          callApi<ActionRequestView[]>("/api/rca/action-requests?limit=50"),
          (value) => sortByTime(expectArray<ActionRequestView>(value, "Action requests"), "created_at"),
        ));
      }
      if (sources.has("agentHealth")) {
        loaders.push(loadSource(
          setAgentHealthState,
          callApi<AgentHealthView[]>("/api/v1/agent-health"),
          (value) => expectArray<AgentHealthView>(value, "Agent health"),
        ));
      }
      if (sources.has("demoScenarios")) {
        loaders.push(loadSource(setDemoScenariosState, callApi("/api/demo/scenarios"), expectDemoScenarios));
      }
      if (sources.has("platformInfo")) {
        loaders.push(loadSource<PlatformInfo | null>(
          setPlatformInfoState,
          callApi<PlatformInfo>("/api/v1/platform/info"),
          (value) => expectObject<PlatformInfo>(value, "Platform info"),
        ));
      }
      if (sources.has("catalogDetail")) {
        loaders.push(loadSource<OperationalCatalogDetail | null>(
          setCatalogDetailState,
          callApi<OperationalCatalogDetail>("/api/v1/catalog"),
          (value) => expectObject<OperationalCatalogDetail>(value, "Catalog"),
        ));
      }
      if (sources.has("catalogOverrideDrafts")) {
        loaders.push(loadSource<CatalogOverrideDraft[]>(
          setCatalogOverrideDraftsState,
          callApi<CatalogOverrideDraft[]>("/api/v1/catalog/overrides/drafts?limit=50"),
          (value) => sortByTime(expectArray<CatalogOverrideDraft>(value, "Catalog override drafts"), "created_at"),
        ));
      }
      if (sources.has("llmDiagnostics")) {
        loaders.push(loadSource<LlmDiagnosticResponse | null>(
          setLlmDiagnosticsState,
          callApi<LlmDiagnosticResponse>("/api/llm/diagnostics"),
          (value) => expectObject<LlmDiagnosticResponse>(value, "LLM diagnostics"),
        ));
      }
      if (sources.has("llmSetupGuide")) {
        loaders.push(loadSource<LlmSetupGuideResponse | null>(
          setLlmSetupGuideState,
          callApi<LlmSetupGuideResponse>("/api/llm/setup"),
          (value) => expectObject<LlmSetupGuideResponse>(value, "LLM setup"),
        ));
      }
      if (sources.has("auditEvents")) {
        loaders.push(loadSource(
          setAuditEventsState,
          callApi<AuditEventView[]>("/api/audit/events?limit=200"),
          (value) => sortByTime(expectArray<AuditEventView>(value, "Audit events"), "created_at"),
        ));
      }
      if (sources.has("notificationHistory")) {
        loaders.push(loadSource(
          setNotificationHistoryState,
          callApi<AuditEventView[]>("/api/notifications/history?limit=50"),
          (value) => sortByTime(expectArray<AuditEventView>(value, "Notification history"), "created_at"),
        ));
      }

      const outcomes = await Promise.all(loaders);
      const refreshedAt = new Date().toISOString();
      if (!outcomes.length || outcomes.some(Boolean)) setLastUpdatedAt(refreshedAt);
      if (outcomes.every(Boolean)) setLastCompleteRefreshAt(refreshedAt);
    } finally {
      inFlight.current = false;
      setLoadingData(false);
    }
  }, [activeView, callApi, currentUser?.role]);

  const loadStates: ConsoleLoadStates = {
    overviewSummary: overviewSummaryState,
    clusters: clustersState,
    actionRequests: actionRequestsState,
    agentHealth: agentHealthState,
    demoScenarios: demoScenariosState,
    platformInfo: platformInfoState,
    catalogDetail: catalogDetailState,
    catalogOverrideDrafts: catalogOverrideDraftsState,
    auditEvents: auditEventsState,
    notificationHistory: notificationHistoryState,
    llmDiagnostics: llmDiagnosticsState,
    llmSetupGuide: llmSetupGuideState,
  };
  const activeLoadStates = Object.fromEntries(
    sourcesForView(activeView, currentUser?.role).map((source) => [source, loadStates[source]]),
  ) as ConsoleLoadStates;

  return {
    loadingData,
    lastUpdatedAt,
    lastCompleteRefreshAt,
    loadStates,
    activeLoadStates,
    overviewSummary: overviewSummaryState.data,
    clusters: clustersState.data,
    actionRequests: actionRequestsState.data,
    agentHealth: agentHealthState.data,
    auditEvents: auditEventsState.data,
    notificationHistory: notificationHistoryState.data,
    demoScenarios: demoScenariosState.data,
    platformInfo: platformInfoState.data,
    catalogDetail: catalogDetailState.data,
    catalogOverrideDrafts: catalogOverrideDraftsState.data,
    llmDiagnostics: llmDiagnosticsState.data,
    llmSetupGuide: llmSetupGuideState.data,
    setAuditEvents: (data: AuditEventView[]) => setAuditEventsState((previous) => ({ ...previous, data })),
    setNotificationHistory: (data: AuditEventView[]) => setNotificationHistoryState((previous) => ({ ...previous, data })),
    setCatalogOverrideDrafts: (data: CatalogOverrideDraft[]) => setCatalogOverrideDraftsState((previous) => ({ ...previous, data })),
    setLlmDiagnostics: (data: LlmDiagnosticResponse | null) => setLlmDiagnosticsState((previous) => ({ ...previous, data })),
    setLlmSetupGuide: (data: LlmSetupGuideResponse | null) => setLlmSetupGuideState((previous) => ({ ...previous, data })),
    loadConsoleData,
  };
}
