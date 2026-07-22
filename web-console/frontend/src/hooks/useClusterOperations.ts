import { useCallback, useState } from "react";
import type { Dispatch, SetStateAction } from "react";

import type {
  AgentTokenRotateResponse,
  ApiCall,
  ClusterCreateForm,
  ClusterDetailState,
  ClusterThresholdSettings,
  ClusterView,
  DeleteClusterDialogState,
  InstallCommandView,
  NotifyFunction,
  TFunction,
} from "../types";

interface ClusterOperationsOptions {
  callApi: ApiCall;
  notify: NotifyFunction;
  t: TFunction;
  routeClusterId?: string;
  navigateToCluster: (clusterId: string) => void;
  navigateToClusterList: () => void;
  loadConsoleData: (background?: boolean) => Promise<void>;
  loadClusterDetail: (cluster: ClusterView | null) => Promise<void>;
  generateInstallCommand: (clusterId: string, backendUrl?: string) => Promise<InstallCommandView>;
  setSelectedCluster: (cluster: ClusterView | null) => void;
  setClusterDetail: Dispatch<SetStateAction<ClusterDetailState | null>>;
  setInstallCommand: Dispatch<SetStateAction<InstallCommandView | null>>;
}

export function useClusterOperations(options: ClusterOperationsOptions) {
  const {
    callApi,
    notify,
    t,
    routeClusterId,
    navigateToCluster,
    navigateToClusterList,
    loadConsoleData,
    loadClusterDetail,
    generateInstallCommand,
    setSelectedCluster,
    setClusterDetail,
    setInstallCommand,
  } = options;
  const [deleteDialog, setDeleteDialog] = useState<DeleteClusterDialogState | null>(null);

  const createCluster = useCallback(async (form: ClusterCreateForm) => {
    const cluster = await callApi<ClusterView>("/api/clusters", {
      method: "POST",
      body: {
        name: form.name,
        environment: form.environment,
        description: form.description,
      },
    });
    notify(t("Cluster created."));
    setSelectedCluster(cluster);
    navigateToCluster(cluster.cluster_id);
    await Promise.all([
      loadConsoleData(true),
      loadClusterDetail(cluster),
      generateInstallCommand(cluster.cluster_id, form.backend_url),
    ]);
  }, [callApi, generateInstallCommand, loadClusterDetail, loadConsoleData, navigateToCluster, notify, setSelectedCluster, t]);

  const deleteCluster = useCallback(async (cluster: ClusterView, confirmName: string) => {
    const query = new URLSearchParams({ confirm_name: confirmName });
    await callApi(`/api/clusters/${encodeURIComponent(cluster.cluster_id)}?${query}`, { method: "DELETE" });
    setDeleteDialog(null);
    setSelectedCluster(null);
    setClusterDetail(null);
    setInstallCommand(null);
    if (routeClusterId === cluster.cluster_id) navigateToClusterList();
    notify(t("Cluster deleted."));
    await loadConsoleData(true);
  }, [callApi, loadConsoleData, navigateToClusterList, notify, routeClusterId, setClusterDetail, setInstallCommand, setSelectedCluster, t]);

  const rotateAgentToken = useCallback(async (cluster: ClusterView) => {
    const result = await callApi<AgentTokenRotateResponse>(
      `/api/clusters/${encodeURIComponent(cluster.cluster_id)}/agent-token/rotate`,
      { method: "POST" },
    );
    notify(t("Agent token rotated."));
    setInstallCommand({
      cluster_id: cluster.cluster_id,
      namespace: "cluster-infra-rca",
      commands: [`New registration token: ${result.agent_token || ""}`],
      notes: [result.note || "", result.expires_at ? `Expires at: ${result.expires_at}` : ""].filter(Boolean),
    });
  }, [callApi, notify, setInstallCommand, t]);

  const startCollection = useCallback(async (cluster: ClusterView, nodeName = "") => {
    await callApi(`/api/clusters/${encodeURIComponent(cluster.cluster_id)}/collection-runs`, {
      method: "POST",
      body: {
        confirmed: true,
        alert_name: "BackendManualCollection",
        node_names: nodeName ? [nodeName] : [],
        requested_collectors: [],
        reason: "Manual evidence collection requested from Web Console.",
        context: { source: "web_console" },
      },
    });
    notify(t("Evidence collection requested."));
    await loadClusterDetail(cluster);
  }, [callApi, loadClusterDetail, notify, t]);

  const updateClusterThresholds = useCallback(async (
    cluster: ClusterView,
    thresholds: Record<string, number>,
    reason: string,
  ) => {
    const settings = await callApi<ClusterThresholdSettings>(
      `/api/clusters/${encodeURIComponent(cluster.cluster_id)}/thresholds`,
      { method: "PUT", body: { thresholds, reason } },
    );
    setClusterDetail((current) => current ? { ...current, thresholds: settings } : current);
    notify(t("Threshold overrides saved."));
  }, [callApi, notify, setClusterDetail, t]);

  const clearClusterThresholds = useCallback(async (cluster: ClusterView) => {
    const settings = await callApi<ClusterThresholdSettings>(
      `/api/clusters/${encodeURIComponent(cluster.cluster_id)}/thresholds`,
      { method: "DELETE" },
    );
    setClusterDetail((current) => current ? { ...current, thresholds: settings } : current);
    notify(t("Threshold overrides cleared."));
  }, [callApi, notify, setClusterDetail, t]);

  return {
    deleteDialog,
    setDeleteDialog,
    createCluster,
    deleteCluster,
    rotateAgentToken,
    startCollection,
    updateClusterThresholds,
    clearClusterThresholds,
  };
}
