import { useCallback } from "react";

import type {
  AnalysisTaskView,
  ApiCall,
  DemoScenarioView,
  DownloadApi,
  IncidentView,
  NotifyFunction,
  TFunction,
} from "../types";

interface OperationalActionsOptions {
  callApi: ApiCall;
  downloadApi: DownloadApi;
  notify: NotifyFunction;
  t: TFunction;
  loadConsoleData: (background?: boolean) => Promise<void>;
}

export function useOperationalActions(options: OperationalActionsOptions) {
  const { callApi, downloadApi, notify, t, loadConsoleData } = options;

  const changeIncidentStatus = useCallback(async (incident: IncidentView, nextStatus: "resolve" | "reopen") => {
    await callApi(`/api/rca/incidents/${encodeURIComponent(incident.incident_id)}/${nextStatus}`, {
      method: "POST",
      body: { confirmed: true, note: "Updated from Web Console." },
    });
    notify(t(nextStatus === "resolve" ? "Incident resolved." : "Incident reopened."));
    await loadConsoleData(true);
  }, [callApi, loadConsoleData, notify, t]);

  const retryAnalysisTask = useCallback(async (task: AnalysisTaskView) => {
    await callApi(`/api/rca/analysis-tasks/${encodeURIComponent(task.task_id)}/retry`, {
      method: "POST",
      body: { confirmed: true, note: "Retry requested from Web Console." },
    });
    notify(t("Analysis task requeued."));
    await loadConsoleData(true);
  }, [callApi, loadConsoleData, notify, t]);

  const runDemoScenario = useCallback(async (scenario: DemoScenarioView, clusterId: string, nodeName: string) => {
    await callApi(`/api/demo/scenarios/${encodeURIComponent(scenario.key)}/run`, {
      method: "POST",
      body: { confirmed: true, cluster_id: clusterId || null, node_name: nodeName || null },
    });
    notify(t("Demo scenario started."));
    await loadConsoleData(true);
  }, [callApi, loadConsoleData, notify, t]);

  const exportReports = useCallback(async (clusterId = "") => {
    const suffix = clusterId ? `?cluster_id=${encodeURIComponent(clusterId)}` : "";
    await downloadApi(
      `/api/rca/reports/export${suffix}`,
      clusterId ? `rca-reports-${clusterId}.json` : "rca-reports.json",
    );
    notify(t("Export downloaded."));
  }, [downloadApi, notify, t]);

  const exportReport = useCallback(async (reportId: string) => {
    await downloadApi(`/api/rca/reports/${encodeURIComponent(reportId)}/export`, `rca-report-${reportId}.json`);
    notify(t("Report exported."));
  }, [downloadApi, notify, t]);

  const exportEvidenceBundle = useCallback(async (reportId: string) => {
    await downloadApi(`/api/rca/reports/${encodeURIComponent(reportId)}/bundle`, `rca-evidence-bundle-${reportId}.zip`);
    notify(t("Evidence bundle downloaded."));
  }, [downloadApi, notify, t]);

  return {
    changeIncidentStatus,
    retryAnalysisTask,
    runDemoScenario,
    exportReports,
    exportReport,
    exportEvidenceBundle,
  };
}
