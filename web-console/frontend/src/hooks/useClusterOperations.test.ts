import { act, renderHook } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import type { ApiCall, ClusterView } from "../types";
import { useClusterOperations } from "./useClusterOperations";

describe("useClusterOperations", () => {
  it("creates a cluster and initializes its detail workflow", async () => {
    const cluster: ClusterView = {
      cluster_id: "cluster-1",
      name: "production",
      environment: "prod",
    };
    const callApi = vi.fn().mockResolvedValue(cluster) as unknown as ApiCall;
    const notify = vi.fn();
    const navigateToCluster = vi.fn();
    const loadConsoleData = vi.fn().mockResolvedValue(undefined);
    const loadClusterDetail = vi.fn().mockResolvedValue(undefined);
    const generateInstallCommand = vi.fn().mockResolvedValue({ cluster_id: cluster.cluster_id, commands: [] });
    const setSelectedCluster = vi.fn();

    const { result } = renderHook(() => useClusterOperations({
      callApi,
      notify,
      t: (key) => key,
      navigateToCluster,
      navigateToClusterList: vi.fn(),
      loadConsoleData,
      loadClusterDetail,
      generateInstallCommand,
      setSelectedCluster,
      setClusterDetail: vi.fn(),
      setInstallCommand: vi.fn(),
    }));

    await act(async () => {
      await result.current.createCluster({
        name: "production",
        environment: "prod",
        description: "primary cluster",
        backend_url: "https://rca.example.test",
      });
    });

    expect(callApi).toHaveBeenCalledWith("/api/clusters", {
      method: "POST",
      body: {
        name: "production",
        environment: "prod",
        description: "primary cluster",
      },
    });
    expect(setSelectedCluster).toHaveBeenCalledWith(cluster);
    expect(navigateToCluster).toHaveBeenCalledWith(cluster.cluster_id);
    expect(generateInstallCommand).toHaveBeenCalledWith(cluster.cluster_id, "https://rca.example.test");
    expect(loadClusterDetail).toHaveBeenCalledWith(cluster);
    expect(loadConsoleData).toHaveBeenCalledWith(true);
    expect(notify).toHaveBeenCalledWith("Cluster created.");
  });
});
