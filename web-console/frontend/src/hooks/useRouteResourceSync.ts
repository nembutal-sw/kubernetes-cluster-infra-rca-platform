import { useEffect } from "react";

import type { ConsoleRoute, ConsoleView } from "../routing";
import type { ClusterView, ReportDetailState } from "../types";

interface RouteResourceSyncOptions {
  currentUser: boolean;
  activeView: ConsoleView;
  route: ConsoleRoute;
  clusters: ClusterView[];
  selectedCluster: ClusterView | null;
  clearClusterDetail: () => void;
  loadClusterDetail: (cluster: ClusterView) => Promise<unknown>;
  selectedReportId: string | null;
  setSelectedReportId: (reportId: string | null) => void;
  setReportDetail: (report: ReportDetailState | null) => void;
}

export function useRouteResourceSync({
  currentUser,
  activeView,
  route,
  clusters,
  selectedCluster,
  clearClusterDetail,
  loadClusterDetail,
  selectedReportId,
  setSelectedReportId,
  setReportDetail,
}: RouteResourceSyncOptions) {
  useEffect(() => {
    if (!currentUser || activeView !== "reports") return;
    if (route.reportId) {
      if (selectedReportId !== route.reportId) setSelectedReportId(route.reportId);
      return;
    }
    if (selectedReportId) {
      setSelectedReportId(null);
      setReportDetail(null);
    }
  }, [activeView, currentUser, route.reportId, selectedReportId, setReportDetail, setSelectedReportId]);

  useEffect(() => {
    if (!currentUser || activeView !== "clusters") return;
    if (!route.clusterId) {
      if (selectedCluster) clearClusterDetail();
      return;
    }
    const cluster = clusters.find((item) => item.cluster_id === route.clusterId);
    if (cluster && selectedCluster?.cluster_id !== cluster.cluster_id) {
      void loadClusterDetail(cluster);
    }
  }, [
    activeView,
    clearClusterDetail,
    clusters,
    currentUser,
    loadClusterDetail,
    route.clusterId,
    selectedCluster,
  ]);
}
