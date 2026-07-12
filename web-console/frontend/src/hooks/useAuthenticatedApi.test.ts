import { act, renderHook } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import type { AuthSession } from "../types";
import { useAuthenticatedApi } from "./useAuthenticatedApi";

describe("useAuthenticatedApi", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("notifies session expiry once for concurrent unauthorized responses", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify({
      code: "authentication_required",
      title: "Authentication required",
      detail: "login required",
      trace_id: "req-auth",
    }), {
      status: 401,
      headers: { "Content-Type": "application/json" },
    })));
    const onUnauthorized = vi.fn();
    const session: AuthSession = { access_token: "expired-token" };
    const { result } = renderHook(() => useAuthenticatedApi(session, onUnauthorized));

    await act(async () => {
      await Promise.allSettled([
        result.current.callApi("/api/clusters"),
        result.current.callApi("/api/rca/incidents"),
      ]);
    });

    expect(onUnauthorized).toHaveBeenCalledTimes(1);
  });
});
