import { useCallback, useState } from "react";

import type {
  AgentHealthView,
  ApiCall,
  ClusterDetailState,
  ClusterThresholdSettings,
  ClusterView,
  EvidenceRequestView,
  InstallCommandView,
  JsonObject,
} from "../types";

function settledArray<T>(result: PromiseSettledResult<unknown>): T[] {
  return result.status === "fulfilled" && Array.isArray(result.value) ? result.value as T[] : [];
}

function settledValue<T>(result: PromiseSettledResult<unknown>): T | null {
  return result.status === "fulfilled" ? result.value as T : null;
}

export function useClusterDetail(callApi: ApiCall) {
  const [selectedCluster, setSelectedCluster] = useState<ClusterView | null>(null);
  const [clusterDetail, setClusterDetail] = useState<ClusterDetailState | null>(null);
  const [installCommand, setInstallCommand] = useState<InstallCommandView | null>(null);

  const generateInstallCommand = useCallback(async (clusterId: string, backendUrl?: string) => {
    const params = new URLSearchParams();
    if (backendUrl) params.set("backend_url", backendUrl);
    const suffix = params.toString() ? `?${params}` : "";
    const command = await callApi<InstallCommandView>(
      `/api/clusters/${encodeURIComponent(clusterId)}/install-command${suffix}`,
    );
    setInstallCommand(command);
    return command;
  }, [callApi]);

  const loadClusterDetail = useCallback(async (cluster: ClusterView | null) => {
    if (!cluster) return;
    setSelectedCluster(cluster);
    const clusterId = cluster.cluster_id;
    const [agents, evidence, topology, thresholds] = await Promise.allSettled([
      callApi<AgentHealthView[]>(`/api/clusters/${encodeURIComponent(clusterId)}/agent-health`),
      callApi<EvidenceRequestView[]>(`/api/clusters/${encodeURIComponent(clusterId)}/evidence-requests?limit=100`),
      callApi<JsonObject>(`/api/clusters/${encodeURIComponent(clusterId)}/topology`),
      callApi<ClusterThresholdSettings>(`/api/clusters/${encodeURIComponent(clusterId)}/thresholds`),
    ]);
    setClusterDetail({
      agents: settledArray<AgentHealthView>(agents),
      evidence: settledArray<EvidenceRequestView>(evidence),
      topology: settledValue<JsonObject>(topology),
      thresholds: settledValue<ClusterThresholdSettings>(thresholds),
    });
  }, [callApi]);

  const clearClusterDetail = useCallback(() => {
    setSelectedCluster(null);
    setClusterDetail(null);
    setInstallCommand(null);
  }, []);

  return {
    selectedCluster,
    setSelectedCluster,
    clusterDetail,
    setClusterDetail,
    installCommand,
    setInstallCommand,
    generateInstallCommand,
    loadClusterDetail,
    clearClusterDetail,
  };
}
