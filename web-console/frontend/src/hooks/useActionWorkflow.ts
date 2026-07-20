import { useCallback, useState } from "react";

import type {
  ActionDialogState,
  ActionRequestView,
  ApiCall,
  NotifyFunction,
  RcaReport,
  TFunction,
} from "../types";

interface ActionWorkflowOptions {
  callApi: ApiCall;
  notify: NotifyFunction;
  t: TFunction;
  loadReportDetail: (reportId: string) => Promise<void>;
  loadConsoleData: (background?: boolean) => Promise<void>;
}

export function useActionWorkflow(options: ActionWorkflowOptions) {
  const { callApi, notify, t, loadReportDetail, loadConsoleData } = options;
  const [actionDialog, setActionDialog] = useState<ActionDialogState | null>(null);

  const executeRecommendedAction = useCallback(async (report: RcaReport, actionIndex: number, note: string) => {
    const response = await callApi<{ message?: string }>(
      `/api/rca/reports/${encodeURIComponent(report.report_id)}/actions/${actionIndex}/execute`,
      { method: "POST", body: { confirmed: true, note } },
    );
    setActionDialog(null);
    notify(response.message || t("Action request updated."));
    await loadReportDetail(report.report_id);
    await loadConsoleData(true);
  }, [callApi, loadConsoleData, loadReportDetail, notify, t]);

  const decideActionRequest = useCallback(async (
    actionRequest: ActionRequestView,
    decision: "approve" | "reject",
    note = "",
  ) => {
    await callApi(`/api/rca/action-requests/${encodeURIComponent(actionRequest.action_request_id)}/${decision}`, {
      method: "POST",
      body: { confirmed: true, note },
    });
    notify(t(decision === "approve" ? "Action request approved." : "Action request rejected."));
    if (actionRequest.report_id) await loadReportDetail(actionRequest.report_id);
    await loadConsoleData(true);
  }, [callApi, loadConsoleData, loadReportDetail, notify, t]);

  const completeManualAction = useCallback(async (actionRequest: ActionRequestView, note: string) => {
    await callApi(`/api/rca/action-requests/${encodeURIComponent(actionRequest.action_request_id)}/complete-manual`, {
      method: "POST",
      body: { confirmed: true, note },
    });
    notify(t("Manual handling completed."));
    if (actionRequest.report_id) await loadReportDetail(actionRequest.report_id);
    await loadConsoleData(true);
  }, [callApi, loadConsoleData, loadReportDetail, notify, t]);

  return {
    actionDialog,
    setActionDialog,
    executeRecommendedAction,
    decideActionRequest,
    completeManualAction,
  };
}
