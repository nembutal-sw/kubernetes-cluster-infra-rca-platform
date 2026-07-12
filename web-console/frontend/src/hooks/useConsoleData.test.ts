import { act, renderHook } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import { ApiError } from "../api/client";
import type { ApiCall, IncidentView, UserAccount } from "../types";
import { useConsoleData } from "./useConsoleData";

const ARRAY_ENDPOINTS = [
  "/api/clusters",
  "/api/rca/reports",
  "/api/rca/analysis-tasks",
  "/api/rca/action-requests",
  "/api/v1/agent-health",
];

describe("useConsoleData", () => {
  it("keeps the last incident data when a refresh fails", async () => {
    const incident: IncidentView = { incident_id: "incident-1", status: "open" };
    let failIncidents = false;
    const request = vi.fn(async (path: string) => {
      if (path === "/api/rca/incidents") {
        if (failIncidents) {
          throw new ApiError({
            status: 500,
            code: "internal_error",
            title: "Internal server error",
            detail: "incident query failed",
            trace_id: "req-incidents",
          });
        }
        return [incident];
      }
      if (ARRAY_ENDPOINTS.some((endpoint) => path.startsWith(endpoint))) return [];
      if (path === "/api/demo/scenarios") return { enabled: false, scenarios: [] };
      return {};
    });
    const user: UserAccount = { user_id: "viewer-1", role: "viewer" };
    const { result } = renderHook(() => useConsoleData(request as unknown as ApiCall, user));

    await act(async () => {
      await result.current.loadConsoleData();
    });
    expect(result.current.incidents).toEqual([incident]);
    expect(result.current.loadStates.incidents.error).toBeUndefined();

    failIncidents = true;
    await act(async () => {
      await result.current.loadConsoleData();
    });

    expect(result.current.incidents).toEqual([incident]);
    expect(result.current.loadStates.incidents).toMatchObject({
      stale: true,
      error: {
        status: 500,
        code: "internal_error",
        trace_id: "req-incidents",
      },
    });
    expect(request.mock.calls.filter(([path]) => path === "/api/v1/agent-health")).toHaveLength(2);
    expect(request.mock.calls.some(([path]) => String(path).includes("/agent-health") && path !== "/api/v1/agent-health"))
      .toBe(false);
  });
});
