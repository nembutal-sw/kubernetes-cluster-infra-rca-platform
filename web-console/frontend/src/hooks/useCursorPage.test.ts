import { act, renderHook, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import { useCursorPage } from "./useCursorPage";
import type { ApiCall, CursorPageResponse } from "../types";

interface Item {
  id: string;
}

describe("useCursorPage", () => {
  it("moves forward and backward with opaque cursors", async () => {
    const callApi = vi.fn(async (path: string): Promise<CursorPageResponse<Item>> => {
      const cursor = new URL(path, "http://console.test").searchParams.get("cursor");
      return cursor
        ? { items: [{ id: "second" }], has_more: false, total: 2, limit: 1 }
        : { items: [{ id: "first" }], next_cursor: "cursor-2", has_more: true, total: 2, limit: 1 };
    }) as unknown as ApiCall;
    const { result } = renderHook(() => useCursorPage<Item>(callApi, "/api/v1/items", {}, undefined, 1));

    await waitFor(() => expect(result.current.page.items[0]?.id).toBe("first"));
    act(() => result.current.next());
    await waitFor(() => expect(result.current.page.items[0]?.id).toBe("second"));
    expect(result.current.pageNumber).toBe(2);

    act(() => result.current.previous());
    await waitFor(() => expect(result.current.page.items[0]?.id).toBe("first"));
    expect(result.current.pageNumber).toBe(1);
  });

  it("returns to the first cursor when filters change", async () => {
    const paths: string[] = [];
    const callApi = vi.fn(async (path: string): Promise<CursorPageResponse<Item>> => {
      paths.push(path);
      return { items: [{ id: "item" }], has_more: false, total: 1, limit: 20 };
    }) as unknown as ApiCall;
    const { result, rerender } = renderHook(
      ({ status }) => useCursorPage<Item>(callApi, "/api/v1/items", { status }, undefined, 20),
      { initialProps: { status: "open" } },
    );

    await waitFor(() => expect(result.current.loading).toBe(false));
    rerender({ status: "resolved" });
    await waitFor(() => expect(paths.some((path) => path.includes("status=resolved"))).toBe(true));

    const filteredPath = [...paths].reverse().find((path: string) => path.includes("status=resolved")) || "";
    expect(filteredPath).not.toContain("cursor=");
    expect(result.current.pageNumber).toBe(1);
  });
});
