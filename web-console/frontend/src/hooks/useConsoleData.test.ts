import { act, renderHook } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import { ApiError } from "../api/client";
import type { ApiCall, AuditEventView, OverviewSummary, UserAccount } from "../types";
import { useConsoleData } from "./useConsoleData";

const OVERVIEW: OverviewSummary = {
  cluster_count: 3,
  report_count: 42,
  open_incident_count: 2,
  reports_last_24_hours: 4,
  analysis_backlog_count: 1,
  analysis_queued_count: 1,
  analysis_processing_count: 0,
  analysis_retry_count: 0,
  analysis_dead_letter_count: 0,
  action_request_count: 5,
  pending_approval_count: 1,
  manual_action_count: 0,
  blocked_action_count: 2,
  agent_count: 3,
  healthy_agent_count: 3,
  stale_agent_count: 0,
  degraded_agent_count: 0,
  offline_agent_count: 0,
  recent_clusters: [],
  recent_reports: [],
  recent_incidents: [],
};

describe("useConsoleData", () => {
  it("loads only the compact summary for the overview route", async () => {
    const request = vi.fn(async (path: string) => {
      if (path === "/api/v1/overview/summary") return OVERVIEW;
      throw new Error(`unexpected endpoint: ${path}`);
    });
    const user: UserAccount = { user_id: "viewer-1", role: "viewer" };
    const { result } = renderHook(() => useConsoleData(request as unknown as ApiCall, user, "overview"));

    await act(async () => {
      await result.current.loadConsoleData();
    });

    expect(result.current.overviewSummary.report_count).toBe(42);
    expect(request).toHaveBeenCalledTimes(1);
    expect(request).toHaveBeenCalledWith("/api/v1/overview/summary");
  });

  it("loads only route-owned cluster data on the incidents route", async () => {
    const request = vi.fn(async (path: string) => {
      if (path === "/api/clusters") return [];
      throw new Error(`unexpected endpoint: ${path}`);
    });
    const user: UserAccount = { user_id: "operator-1", role: "operator" };
    const { result } = renderHook(() => useConsoleData(request as unknown as ApiCall, user, "incidents"));

    await act(async () => {
      await result.current.loadConsoleData();
    });

    expect(request).toHaveBeenCalledTimes(1);
    expect(request).not.toHaveBeenCalledWith("/api/rca/incidents");
    expect(Object.keys(result.current.activeLoadStates)).toEqual(["clusters"]);
  });

  it("keeps the last route data when a refresh fails", async () => {
    const auditEvent: AuditEventView = { audit_event_id: "audit-1", event_type: "auth.login" };
    let failAudit = false;
    const request = vi.fn(async (path: string) => {
      if (path === "/api/audit/events?limit=200") {
        if (failAudit) {
          throw new ApiError({
            status: 500,
            code: "internal_error",
            title: "Internal server error",
            detail: "audit query failed",
            trace_id: "req-audit",
          });
        }
        return [auditEvent];
      }
      throw new Error(`unexpected endpoint: ${path}`);
    });
    const user: UserAccount = { user_id: "auditor-1", role: "auditor" };
    const { result } = renderHook(() => useConsoleData(request as unknown as ApiCall, user, "audit"));

    await act(async () => {
      await result.current.loadConsoleData();
    });
    expect(result.current.auditEvents).toEqual([auditEvent]);

    failAudit = true;
    await act(async () => {
      await result.current.loadConsoleData();
    });

    expect(result.current.auditEvents).toEqual([auditEvent]);
    expect(result.current.activeLoadStates.auditEvents).toMatchObject({
      stale: true,
      error: { status: 500, code: "internal_error", trace_id: "req-audit" },
    });
  });
});
