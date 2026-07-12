import { useCallback, useRef, useState } from "react";
import type { Dispatch, SetStateAction } from "react";

import { ApiError, apiErrorDetails } from "../api/client";
import { sortByTime } from "../lib/consoleUtils";
import type {
  ActionRequestView,
  AgentHealthView,
  AnalysisTaskView,
  ApiCall,
  AuditEventView,
  CatalogOverrideDraft,
  ClusterView,
  ConsoleLoadStates,
  DemoScenarioView,
  IncidentView,
  LlmDiagnosticResponse,
  LlmSetupGuideResponse,
  LoadState,
  OperationalCatalogDetail,
  PlatformInfo,
  RcaReport,
  UserAccount,
} from "../types";

type SourceSetter<T> = Dispatch<SetStateAction<LoadState<T>>>;

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
    setter({
      data,
      loading: false,
      loadedAt: new Date().toISOString(),
      stale: false,
    });
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

export function useConsoleData(callApi: ApiCall, currentUser: UserAccount | null) {
  const inFlight = useRef(false);
  const [loadingData, setLoadingData] = useState(false);
  const [lastUpdatedAt, setLastUpdatedAt] = useState<string>();
  const [lastCompleteRefreshAt, setLastCompleteRefreshAt] = useState<string>();
  const [clustersState, setClustersState] = useState(() => initialState<ClusterView[]>([]));
  const [reportsState, setReportsState] = useState(() => initialState<RcaReport[]>([]));
  const [incidentsState, setIncidentsState] = useState(() => initialState<IncidentView[]>([]));
  const [analysisTasksState, setAnalysisTasksState] = useState(() => initialState<AnalysisTaskView[]>([]));
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
      const loaders: Promise<boolean>[] = [
        loadSource(setClustersState, callApi<ClusterView[]>("/api/clusters"), (value) => expectArray<ClusterView>(value, "Clusters")),
        loadSource(setReportsState, callApi<RcaReport[]>("/api/rca/reports"), (value) => sortByTime(expectArray<RcaReport>(value, "Reports"), "created_at")),
        loadSource(setIncidentsState, callApi<IncidentView[]>("/api/rca/incidents"), (value) => sortByTime(expectArray<IncidentView>(value, "Incidents"), "last_seen_at")),
        loadSource(setAnalysisTasksState, callApi<AnalysisTaskView[]>("/api/rca/analysis-tasks?limit=300"), (value) => sortByTime(expectArray<AnalysisTaskView>(value, "Analysis tasks"), "created_at")),
        loadSource(setActionRequestsState, callApi<ActionRequestView[]>("/api/rca/action-requests"), (value) => sortByTime(expectArray<ActionRequestView>(value, "Action requests"), "created_at")),
        loadSource(setAgentHealthState, callApi<AgentHealthView[]>("/api/v1/agent-health"), (value) => expectArray<AgentHealthView>(value, "Agent health")),
        loadSource(setDemoScenariosState, callApi("/api/demo/scenarios"), expectDemoScenarios),
        loadSource<PlatformInfo | null>(setPlatformInfoState, callApi<PlatformInfo>("/api/v1/platform/info"), (value) => expectObject<PlatformInfo>(value, "Platform info")),
        loadSource<OperationalCatalogDetail | null>(setCatalogDetailState, callApi<OperationalCatalogDetail>("/api/v1/catalog"), (value) => expectObject<OperationalCatalogDetail>(value, "Catalog")),
        loadSource<LlmDiagnosticResponse | null>(setLlmDiagnosticsState, callApi<LlmDiagnosticResponse>("/api/llm/diagnostics"), (value) => expectObject<LlmDiagnosticResponse>(value, "LLM diagnostics")),
        loadSource<LlmSetupGuideResponse | null>(setLlmSetupGuideState, callApi<LlmSetupGuideResponse>("/api/llm/setup"), (value) => expectObject<LlmSetupGuideResponse>(value, "LLM setup")),
      ];

      if (hasCatalogOverrideDraftAccess(currentUser?.role)) {
        loaders.push(loadSource(
          setCatalogOverrideDraftsState,
          callApi<CatalogOverrideDraft[]>("/api/v1/catalog/overrides/drafts?limit=50"),
          (value) => sortByTime(expectArray<CatalogOverrideDraft>(value, "Catalog override drafts"), "created_at"),
        ));
      } else {
        setCatalogOverrideDraftsState(initialState([]));
      }

      if (hasAuditAccess(currentUser?.role)) {
        loaders.push(
          loadSource(
            setAuditEventsState,
            callApi<AuditEventView[]>("/api/audit/events?limit=200"),
            (value) => sortByTime(expectArray<AuditEventView>(value, "Audit events"), "created_at"),
          ),
          loadSource(
            setNotificationHistoryState,
            callApi<AuditEventView[]>("/api/notifications/history?limit=50"),
            (value) => sortByTime(expectArray<AuditEventView>(value, "Notification history"), "created_at"),
          ),
        );
      } else {
        setAuditEventsState(initialState([]));
        setNotificationHistoryState(initialState([]));
      }

      const outcomes = await Promise.all(loaders);
      const refreshedAt = new Date().toISOString();
      if (outcomes.some(Boolean)) setLastUpdatedAt(refreshedAt);
      if (outcomes.every(Boolean)) setLastCompleteRefreshAt(refreshedAt);
    } finally {
      inFlight.current = false;
      setLoadingData(false);
    }
  }, [callApi, currentUser?.role]);

  const loadStates: ConsoleLoadStates = {
    clusters: clustersState,
    reports: reportsState,
    incidents: incidentsState,
    analysisTasks: analysisTasksState,
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

  return {
    loadingData,
    lastUpdatedAt,
    lastCompleteRefreshAt,
    loadStates,
    clusters: clustersState.data,
    reports: reportsState.data,
    incidents: incidentsState.data,
    analysisTasks: analysisTasksState.data,
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
