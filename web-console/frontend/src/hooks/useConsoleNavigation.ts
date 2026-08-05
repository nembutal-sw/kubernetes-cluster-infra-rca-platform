import { useCallback, useEffect } from "react";
import { useLocation, useNavigate } from "react-router";

import { NAV_ITEMS } from "../constants";
import {
  clusterPath,
  incidentPath,
  parseConsoleRoute,
  pathForView,
  reportPath,
} from "../routing";
import type { UserAccount } from "../types";

export function useConsoleNavigation(currentUser: UserAccount | null) {
  const location = useLocation();
  const navigate = useNavigate();
  const route = parseConsoleRoute(location.pathname);
  const requestedNav = NAV_ITEMS.find((item) => item.id === route.view);
  const routeAllowed = !currentUser || !requestedNav?.roles || requestedNav.roles.includes(currentUser.role);
  const activeView = routeAllowed ? route.view : "overview";

  useEffect(() => {
    if (!route.valid || location.pathname !== route.canonicalPath) {
      navigate(route.canonicalPath, { replace: true });
    }
  }, [location.pathname, navigate, route.canonicalPath, route.valid]);

  useEffect(() => {
    if (currentUser && !routeAllowed) {
      navigate("/overview", { replace: true });
    }
  }, [currentUser, navigate, routeAllowed]);

  const navigateToView = useCallback((view: string) => {
    navigate(pathForView(view));
  }, [navigate]);

  const navigateToCluster = useCallback((clusterId: string) => {
    navigate(clusterPath(clusterId));
  }, [navigate]);

  const navigateToClusterList = useCallback(() => {
    navigate("/clusters", { replace: true });
  }, [navigate]);

  const openReport = useCallback((reportId: string) => {
    if (reportId) navigate(reportPath(reportId));
  }, [navigate]);

  const openIncident = useCallback((incidentId: string) => {
    if (incidentId) navigate(incidentPath(incidentId));
  }, [navigate]);

  const returnToActiveView = useCallback(() => {
    navigate(pathForView(activeView), { replace: true });
  }, [activeView, navigate]);

  const resetToOverview = useCallback(() => {
    navigate("/overview", { replace: true });
  }, [navigate]);

  return {
    route,
    activeView,
    navigateToView,
    navigateToCluster,
    navigateToClusterList,
    openReport,
    openIncident,
    returnToActiveView,
    resetToOverview,
  };
}
