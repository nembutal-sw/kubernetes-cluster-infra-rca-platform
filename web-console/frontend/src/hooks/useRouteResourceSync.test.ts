import { renderHook } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import type { ConsoleRoute } from "../routing";
import type { ClusterView } from "../types";
import { useRouteResourceSync } from "./useRouteResourceSync";

describe("useRouteResourceSync", () => {
  it("synchronizes report detail selection with the route", () => {
    const setSelectedReportId = vi.fn();
    const setReportDetail = vi.fn();
    const base = {
      currentUser: true,
      activeView: "reports" as const,
      clusters: [],
      selectedCluster: null,
      clearClusterDetail: vi.fn(),
      loadClusterDetail: vi.fn().mockResolvedValue(undefined),
      selectedReportId: "report-old",
      setSelectedReportId,
      setReportDetail,
    };
    const detailRoute: ConsoleRoute = {
      view: "reports",
      valid: true,
      canonicalPath: "/reports/report-new",
      reportId: "report-new",
    };
    const listRoute: ConsoleRoute = {
      view: "reports",
      valid: true,
      canonicalPath: "/reports",
    };

    const { rerender } = renderHook(
      ({ route, selectedReportId }) => useRouteResourceSync({ ...base, route, selectedReportId }),
      { initialProps: { route: detailRoute, selectedReportId: "report-old" as string | null } },
    );
    expect(setSelectedReportId).toHaveBeenCalledWith("report-new");

    rerender({ route: listRoute, selectedReportId: "report-new" });
    expect(setSelectedReportId).toHaveBeenLastCalledWith(null);
    expect(setReportDetail).toHaveBeenCalledWith(null);
  });

  it("loads cluster detail and clears it on the cluster list route", () => {
    const cluster: ClusterView = {
      cluster_id: "cluster-1",
      name: "production",
      environment: "prod",
    };
    const loadClusterDetail = vi.fn().mockResolvedValue(undefined);
    const clearClusterDetail = vi.fn();
    const base = {
      currentUser: true,
      activeView: "clusters" as const,
      clusters: [cluster],
      setSelectedReportId: vi.fn(),
      setReportDetail: vi.fn(),
      selectedReportId: null,
      clearClusterDetail,
      loadClusterDetail,
    };
    const detailRoute: ConsoleRoute = {
      view: "clusters",
      valid: true,
      canonicalPath: "/clusters/cluster-1",
      clusterId: "cluster-1",
    };
    const listRoute: ConsoleRoute = {
      view: "clusters",
      valid: true,
      canonicalPath: "/clusters",
    };

    const { rerender } = renderHook(
      ({ route, selectedCluster }) => useRouteResourceSync({ ...base, route, selectedCluster }),
      { initialProps: { route: detailRoute, selectedCluster: null as ClusterView | null } },
    );
    expect(loadClusterDetail).toHaveBeenCalledWith(cluster);

    rerender({ route: listRoute, selectedCluster: cluster });
    expect(clearClusterDetail).toHaveBeenCalledOnce();
  });
});
