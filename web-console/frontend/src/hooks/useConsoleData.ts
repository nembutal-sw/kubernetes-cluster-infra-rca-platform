import { useCallback, useState } from "react";

import { sortByTime } from "../lib/consoleUtils";
import type {
  ActionRequestView,
  AgentHealthView,
  AnalysisTaskView,
  ApiCall,
  AuditEventView,
  ClusterView,
  DemoScenarioView,
  IncidentView,
  LlmDiagnosticResponse,
  LlmSetupGuideResponse,
  PlatformInfo,
  RcaReport,
  TFunction,
  UserAccount,
} from "../types";

type NotifyFunction = (message: string, tone?: string) => void;

function hasAuditAccess(role?: string): boolean {
  return role === "admin" || role === "auditor";
}

function settledArray<T>(result: PromiseSettledResult<unknown>): T[] {
  return result.status === "fulfilled" && Array.isArray(result.value) ? result.value as T[] : [];
}

function settledValue<T>(result: PromiseSettledResult<unknown>): T | null {
  return result.status === "fulfilled" ? result.value as T : null;
}

function errorMessage(error: unknown, fallback: string): string {
  return error instanceof Error && error.message ? error.message : fallback;
}

export function useConsoleData(
  callApi: ApiCall,
  currentUser: UserAccount | null,
  notify: NotifyFunction,
  t: TFunction,
) {
  const [loadingData, setLoadingData] = useState(false);
  const [clusters, setClusters] = useState<ClusterView[]>([]);
  const [reports, setReports] = useState<RcaReport[]>([]);
  const [incidents, setIncidents] = useState<IncidentView[]>([]);
  const [analysisTasks, setAnalysisTasks] = useState<AnalysisTaskView[]>([]);
  const [actionRequests, setActionRequests] = useState<ActionRequestView[]>([]);
  const [agentHealth, setAgentHealth] = useState<AgentHealthView[]>([]);
  const [auditEvents, setAuditEvents] = useState<AuditEventView[]>([]);
  const [notificationHistory, setNotificationHistory] = useState<AuditEventView[]>([]);
  const [demoScenarios, setDemoScenarios] = useState<DemoScenarioView[]>([]);
  const [platformInfo, setPlatformInfo] = useState<PlatformInfo | null>(null);
  const [llmDiagnostics, setLlmDiagnostics] = useState<LlmDiagnosticResponse | null>(null);
  const [llmSetupGuide, setLlmSetupGuide] = useState<LlmSetupGuideResponse | null>(null);

  const loadConsoleData = useCallback(async (silent = false) => {
    if (!silent) setLoadingData(true);
    try {
      const [
        clusterResult,
        reportResult,
        incidentResult,
        taskResult,
        actionRequestResult,
        scenarioResult,
        platformResult,
        llmDiagnosticsResult,
        llmSetupResult,
      ] = await Promise.allSettled([
        callApi<ClusterView[]>("/api/clusters"),
        callApi<RcaReport[]>("/api/rca/reports"),
        callApi<IncidentView[]>("/api/rca/incidents"),
        callApi<AnalysisTaskView[]>("/api/rca/analysis-tasks?limit=300"),
        callApi<ActionRequestView[]>("/api/rca/action-requests"),
        callApi<DemoScenarioView[]>("/api/demo/scenarios"),
        callApi<PlatformInfo>("/api/v1/platform/info"),
        callApi<LlmDiagnosticResponse>("/api/llm/diagnostics"),
        callApi<LlmSetupGuideResponse>("/api/llm/setup"),
      ]);

      const clusterItems = settledArray<ClusterView>(clusterResult);
      setClusters(clusterItems);
      setReports(sortByTime(settledArray<RcaReport>(reportResult), "created_at"));
      setIncidents(sortByTime(settledArray<IncidentView>(incidentResult), "last_seen_at"));
      setAnalysisTasks(sortByTime(settledArray<AnalysisTaskView>(taskResult), "created_at"));
      setActionRequests(sortByTime(settledArray<ActionRequestView>(actionRequestResult), "created_at"));
      setDemoScenarios(settledArray<DemoScenarioView>(scenarioResult));
      setPlatformInfo(settledValue<PlatformInfo>(platformResult));
      setLlmDiagnostics(settledValue<LlmDiagnosticResponse>(llmDiagnosticsResult));
      setLlmSetupGuide(settledValue<LlmSetupGuideResponse>(llmSetupResult));

      if (hasAuditAccess(currentUser?.role)) {
        const [auditResult, notificationResult] = await Promise.allSettled([
          callApi<AuditEventView[]>("/api/audit/events?limit=200"),
          callApi<AuditEventView[]>("/api/notifications/history?limit=50"),
        ]);
        setAuditEvents(sortByTime(settledArray<AuditEventView>(auditResult), "created_at"));
        setNotificationHistory(sortByTime(settledArray<AuditEventView>(notificationResult), "created_at"));
      } else {
        setAuditEvents([]);
        setNotificationHistory([]);
      }

      if (clusterItems.length) {
        const healthResults = await Promise.allSettled(
          clusterItems.map((cluster) => callApi<AgentHealthView[]>(
            `/api/clusters/${encodeURIComponent(cluster.cluster_id)}/agent-health`,
          )),
        );
        setAgentHealth(healthResults.flatMap((result) => settledArray<AgentHealthView>(result)));
      } else {
        setAgentHealth([]);
      }
    } catch (error) {
      notify(errorMessage(error, t("Failed to load console data.")), "danger");
    } finally {
      setLoadingData(false);
    }
  }, [callApi, currentUser?.role, notify, t]);

  return {
    loadingData,
    clusters,
    reports,
    incidents,
    analysisTasks,
    actionRequests,
    agentHealth,
    auditEvents,
    notificationHistory,
    demoScenarios,
    platformInfo,
    llmDiagnostics,
    llmSetupGuide,
    setAuditEvents,
    setNotificationHistory,
    setLlmDiagnostics,
    setLlmSetupGuide,
    loadConsoleData,
  };
}
