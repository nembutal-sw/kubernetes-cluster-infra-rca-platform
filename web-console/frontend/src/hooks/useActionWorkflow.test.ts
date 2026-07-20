import { act, renderHook } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import type { ApiCall, RcaReport } from "../types";
import { useActionWorkflow } from "./useActionWorkflow";

describe("useActionWorkflow", () => {
  it("submits a confirmed action request and refreshes report state", async () => {
    const report = { report_id: "report-1" } as RcaReport;
    const callApi = vi.fn().mockResolvedValue({ message: "Approval request created." }) as unknown as ApiCall;
    const notify = vi.fn();
    const loadReportDetail = vi.fn().mockResolvedValue(undefined);
    const loadConsoleData = vi.fn().mockResolvedValue(undefined);
    const { result } = renderHook(() => useActionWorkflow({
      callApi,
      notify,
      t: (key) => key,
      loadReportDetail,
      loadConsoleData,
    }));

    await act(async () => {
      result.current.setActionDialog({ report, action: { action_id: "restart-kubelet" }, index: 2 });
    });
    await act(async () => {
      await result.current.executeRecommendedAction(report, 2, "reviewed by operator");
    });

    expect(callApi).toHaveBeenCalledWith("/api/rca/reports/report-1/actions/2/execute", {
      method: "POST",
      body: { confirmed: true, note: "reviewed by operator" },
    });
    expect(result.current.actionDialog).toBeNull();
    expect(notify).toHaveBeenCalledWith("Approval request created.");
    expect(loadReportDetail).toHaveBeenCalledWith(report.report_id);
    expect(loadConsoleData).toHaveBeenCalledWith(true);
  });
});
