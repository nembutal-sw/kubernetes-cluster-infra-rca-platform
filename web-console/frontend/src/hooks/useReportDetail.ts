import { useCallback, useEffect, useState } from "react";

import type {
  ActionExecutionView,
  ActionRequestView,
  ApiCall,
  EvidenceBundleManifest,
  JsonObject,
  RcaReport,
  ReportDetailState,
  TFunction,
  UserAccount,
} from "../types";

type NotifyFunction = (message: string, tone?: string) => void;

function hasExecutionAccess(role?: string): boolean {
  return role === "admin" || role === "operator" || role === "auditor";
}

function hasBundleManifestAccess(role?: string): boolean {
  return role === "admin" || role === "operator";
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

export function useReportDetail(
  callApi: ApiCall,
  currentUser: UserAccount | null,
  notify: NotifyFunction,
  t: TFunction,
) {
  const [selectedReportId, setSelectedReportId] = useState<string | null>(null);
  const [reportDetail, setReportDetail] = useState<ReportDetailState | null>(null);

  const loadReportDetail = useCallback(async (reportId: string) => {
    try {
      const report = await callApi<RcaReport>(`/api/rca/reports/${encodeURIComponent(reportId)}`);
      const incidentId = report.incident_id;
      const [actionReq, executions, timeline, bundleManifest] = await Promise.allSettled([
        callApi<ActionRequestView[]>(`/api/rca/action-requests?report_id=${encodeURIComponent(reportId)}`),
        hasExecutionAccess(currentUser?.role)
          ? callApi<ActionExecutionView[]>(`/api/rca/action-executions?report_id=${encodeURIComponent(reportId)}`)
          : Promise.resolve([]),
        incidentId ? callApi<JsonObject>(`/api/rca/incidents/${encodeURIComponent(incidentId)}/timeline`) : Promise.resolve(null),
        hasBundleManifestAccess(currentUser?.role)
          ? callApi<EvidenceBundleManifest>(`/api/rca/reports/${encodeURIComponent(reportId)}/bundle/manifest`)
          : Promise.resolve(null),
      ]);

      setReportDetail({
        report,
        actionRequests: settledArray<ActionRequestView>(actionReq),
        actionExecutions: settledArray<ActionExecutionView>(executions),
        timeline: settledValue<JsonObject>(timeline),
        bundleManifest: settledValue<EvidenceBundleManifest>(bundleManifest),
      });
    } catch (error) {
      notify(errorMessage(error, t("Failed to load report.")), "danger");
    }
  }, [callApi, currentUser?.role, notify, t]);

  useEffect(() => {
    if (selectedReportId) {
      void loadReportDetail(selectedReportId);
    } else {
      setReportDetail(null);
    }
  }, [loadReportDetail, selectedReportId]);

  return {
    selectedReportId,
    setSelectedReportId,
    reportDetail,
    setReportDetail,
    loadReportDetail,
  };
}
