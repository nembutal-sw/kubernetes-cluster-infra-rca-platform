import { afterEach, describe, expect, it, vi } from "vitest";

import { ApiError, requestApi } from "./client";

describe("requestApi", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("preserves the structured backend error contract", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify({
      code: "database_unavailable",
      title: "Service unavailable",
      detail: "database operation failed",
      suggestion: "Retry after the platform dependency recovers.",
      trace_id: "req-test-123",
    }), {
      status: 503,
      statusText: "Service Unavailable",
      headers: { "Content-Type": "application/json" },
    })));

    const error = await requestApi("/api/clusters").catch((caught) => caught);

    expect(error).toBeInstanceOf(ApiError);
    expect(error).toMatchObject({
      status: 503,
      code: "database_unavailable",
      detail: "database operation failed",
      traceId: "req-test-123",
    });
  });

  it("uses the response header as a trace fallback", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response("upstream timeout", {
      status: 504,
      statusText: "Gateway Timeout",
      headers: { "X-Request-ID": "req-header-456" },
    })));

    const error = await requestApi("/api/rca/incidents").catch((caught) => caught);

    expect(error).toMatchObject({
      status: 504,
      detail: "upstream timeout",
      traceId: "req-header-456",
    });
  });
});
