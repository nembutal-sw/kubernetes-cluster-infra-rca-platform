import type { Dispatch, SetStateAction } from "react";

import type { Locale } from "../constants";
import type { useActionWorkflow } from "../hooks/useActionWorkflow";
import type { useAuditSearch } from "../hooks/useAuditSearch";
import type { useClusterDetail } from "../hooks/useClusterDetail";
import type { useClusterOperations } from "../hooks/useClusterOperations";
import type { useConsoleData } from "../hooks/useConsoleData";
import type { useConsoleNavigation } from "../hooks/useConsoleNavigation";
import type { useOperationalActions } from "../hooks/useOperationalActions";
import type { useReportDetail } from "../hooks/useReportDetail";
import type { useSettingsOperations } from "../hooks/useSettingsOperations";
import { copyText } from "../lib/consoleUtils";
import { AuditView } from "../pages/Audit";
import { ClustersView } from "../pages/Clusters";
import { IncidentsView } from "../pages/Incidents";
import { OverviewView } from "../pages/Overview";
import { PipelineView } from "../pages/Pipeline";
import { ReportsView } from "../pages/Reports";
import { SettingsView } from "../pages/Settings";
import { WebhooksView } from "../pages/Webhooks";
import type { ApiCall, ClusterView, NotifyFunction, TFunction, UserAccount } from "../types";
import { DataStatusBanner } from "./DataStatusBanner";
import { RouteStatusNotice } from "./RouteStatusNotice";

type ConsoleDataController = ReturnType<typeof useConsoleData>;
type ClusterDetailController = ReturnType<typeof useClusterDetail>;
type ClusterOperationsController = ReturnType<typeof useClusterOperations>;
type ReportDetailController = ReturnType<typeof useReportDetail>;
type ActionWorkflowController = ReturnType<typeof useActionWorkflow>;
type OperationalActionsController = ReturnType<typeof useOperationalActions>;
type AuditSearchController = ReturnType<typeof useAuditSearch>;
type SettingsOperationsController = ReturnType<typeof useSettingsOperations>;
type NavigationController = ReturnType<typeof useConsoleNavigation>;

interface ConsoleViewHostProps {
  callApi: ApiCall;
  currentUser: UserAccount;
  locale: Locale;
  setLocale: Dispatch<SetStateAction<Locale>>;
  t: TFunction;
  notify: NotifyFunction;
  data: ConsoleDataController;
  clusterDetail: ClusterDetailController;
  clusterOperations: ClusterOperationsController;
  reportDetail: ReportDetailController;
  actionWorkflow: ActionWorkflowController;
  operationalActions: OperationalActionsController;
  auditSearch: AuditSearchController;
  settingsOperations: SettingsOperationsController;
  navigation: NavigationController;
}

export function ConsoleViewHost({
  callApi,
  currentUser,
  locale,
  setLocale,
  t,
  notify,
  data,
  clusterDetail,
  clusterOperations,
  reportDetail,
  actionWorkflow,
  operationalActions,
  auditSearch,
  settingsOperations,
  navigation,
}: ConsoleViewHostProps) {
  const { activeView, route } = navigation;
  const webhookEndpoint = `${window.location.origin.replace(/\/$/, "")}/api/webhooks/alertmanager`;
  const routeResourceMissing = activeView === "reports" && route.reportId
    ? reportDetail.reportMissing
    : activeView === "clusters" && route.clusterId && data.loadStates.clusters.loadedAt && !data.loadStates.clusters.error
      ? !data.clusters.some((item) => item.cluster_id === route.clusterId)
      : false;
  const routeResourceId = route.clusterId || route.reportId || route.incidentId || "";

  async function openCluster(cluster: ClusterView | null) {
    if (!cluster) return;
    navigation.navigateToCluster(cluster.cluster_id);
    await clusterDetail.loadClusterDetail(cluster);
  }

  function renderActiveView() {
    switch (activeView) {
      case "overview":
        return (
          <OverviewView
            summary={data.overviewSummary}
            onNavigate={navigation.navigateToView}
            onOpenReport={navigation.openReport}
            onOpenCluster={openCluster}
            webhookEndpoint={webhookEndpoint}
            t={t}
          />
        );
      case "clusters":
        return (
          <ClustersView
            clusters={data.clusters}
            selectedCluster={clusterDetail.selectedCluster}
            clusterDetail={clusterDetail.clusterDetail}
            agentHealth={data.agentHealth}
            installCommand={clusterDetail.installCommand}
            currentUser={currentUser}
            onCreate={clusterOperations.createCluster}
            onSelect={openCluster}
            onGenerateInstall={clusterDetail.generateInstallCommand}
            onStartCollection={clusterOperations.startCollection}
            onUpdateThresholds={clusterOperations.updateClusterThresholds}
            onClearThresholds={clusterOperations.clearClusterThresholds}
            onUpdateEnrollment={clusterOperations.updateAgentEnrollment}
            onDelete={(cluster) => clusterOperations.setDeleteDialog({ cluster })}
            onRotateToken={clusterOperations.rotateAgentToken}
            onCopy={(text) => copyText(text, notify)}
            t={t}
          />
        );
      case "reports":
        return (
          <ReportsView
            callApi={callApi}
            clusters={data.clusters}
            refreshToken={data.lastUpdatedAt}
            selectedReportId={reportDetail.selectedReportId}
            setSelectedReportId={navigation.openReport}
            detail={reportDetail.reportDetail}
            currentUser={currentUser}
            onPrepareAction={(report, action, index) => actionWorkflow.setActionDialog({ report, action, index })}
            onDecideAction={actionWorkflow.decideActionRequest}
            onCompleteManual={actionWorkflow.completeManualAction}
            onExportReport={operationalActions.exportReport}
            onExportBundle={operationalActions.exportEvidenceBundle}
            onExportAll={() => operationalActions.exportReports()}
            platformInfo={data.platformInfo}
            onCopy={(text) => copyText(text, notify)}
            t={t}
          />
        );
      case "incidents":
        return (
          <IncidentsView
            callApi={callApi}
            clusters={data.clusters}
            refreshToken={data.lastUpdatedAt}
            selectedIncidentId={route.incidentId}
            onSelectIncident={navigation.openIncident}
            onOpenReport={navigation.openReport}
            onChangeStatus={operationalActions.changeIncidentStatus}
            currentUser={currentUser}
            t={t}
          />
        );
      case "pipeline":
        return (
          <PipelineView
            callApi={callApi}
            refreshToken={data.lastUpdatedAt}
            summary={data.overviewSummary}
            actionRequests={data.actionRequests}
            demoScenarios={data.demoScenarios}
            clusters={data.clusters}
            onRetry={operationalActions.retryAnalysisTask}
            onRunDemo={operationalActions.runDemoScenario}
            t={t}
          />
        );
      case "audit":
        return (
          <AuditView
            events={data.auditEvents}
            onSearch={auditSearch.searchAudit}
            onExport={auditSearch.exportAudit}
            t={t}
          />
        );
      case "webhooks":
        return (
          <WebhooksView
            endpoint={webhookEndpoint}
            onCopy={(text) => copyText(text, notify)}
            t={t}
          />
        );
      case "settings":
        return (
          <SettingsView
            locale={locale}
            setLocale={setLocale}
            platformInfo={data.platformInfo}
            catalogDetail={data.catalogDetail}
            catalogOverrideDrafts={data.catalogOverrideDrafts}
            llmDiagnostics={data.llmDiagnostics}
            llmSetupGuide={data.llmSetupGuide}
            notificationHistory={data.notificationHistory}
            currentUser={currentUser}
            onChangeLoginId={settingsOperations.changeLoginId}
            onChangePassword={settingsOperations.changePassword}
            onTestNotification={settingsOperations.testNotificationDelivery}
            onTestLlm={settingsOperations.testLlmConnection}
            onPreviewCatalogOverride={settingsOperations.previewCatalogOverride}
            onCreateCatalogOverrideDraft={settingsOperations.createCatalogOverrideDraft}
            onDecideCatalogOverrideDraft={settingsOperations.decideCatalogOverrideDraft}
            onLoadCatalogOverrideHandoff={settingsOperations.loadCatalogOverrideHandoff}
            onCreateCatalogGitOpsChange={settingsOperations.createCatalogGitOpsChange}
            onLoadCatalogGitOpsChanges={settingsOperations.loadCatalogGitOpsChanges}
            onRetryGitOpsChange={settingsOperations.retryGitOpsChange}
            onUpdateGitOpsOutcome={settingsOperations.updateGitOpsOutcome}
            t={t}
          />
        );
    }
  }

  return (
    <>
      <DataStatusBanner
        states={data.activeLoadStates}
        lastCompleteRefreshAt={data.lastCompleteRefreshAt}
        onRetry={() => data.loadConsoleData(false)}
        t={t}
      />
      {routeResourceMissing && (
        <RouteStatusNotice
          resourceId={routeResourceId}
          onReturn={navigation.returnToActiveView}
          t={t}
        />
      )}
      {renderActiveView()}
    </>
  );
}
