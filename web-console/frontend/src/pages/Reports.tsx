import { useState } from "react";

import { EmptyState, Icon, PageHeader } from "../components/common";
import { CursorPager } from "../components/CursorPager";
import { ReportDetail } from "../features/reports/ReportDetail";
import { ReportListPanel } from "../features/reports/ReportListPanel";
import { useCursorPage, useDebouncedValue } from "../hooks/useCursorPage";
import type {
  ActionRequestView,
  ApiCall,
  ClusterView,
  PlatformInfo,
  RcaReport,
  RecommendedAction,
  ReportDetailState,
  TFunction,
  UserAccount,
} from "../types";

interface ReportsViewProps {
  callApi: ApiCall;
  clusters: ClusterView[];
  refreshToken?: string;
  selectedReportId: string | null;
  setSelectedReportId: (reportId: string) => void;
  detail: ReportDetailState | null;
  currentUser: UserAccount;
  onPrepareAction: (report: RcaReport, action: RecommendedAction, index: number) => void;
  onDecideAction: (actionRequest: ActionRequestView, decision: "approve" | "reject", note?: string) => Promise<void> | void;
  onCompleteManual: (actionRequest: ActionRequestView, note: string) => Promise<void> | void;
  onExportReport: (reportId: string) => Promise<void> | void;
  onExportBundle: (reportId: string) => Promise<void> | void;
  onExportAll: () => Promise<void> | void;
  platformInfo: PlatformInfo | null;
  onCopy: (text: string) => void;
  t: TFunction;
}

export function ReportsView({ callApi, clusters, refreshToken, selectedReportId, setSelectedReportId, detail, currentUser, onPrepareAction, onDecideAction, onCompleteManual, onExportReport, onExportBundle, onExportAll, platformInfo, onCopy, t }: ReportsViewProps) {
  const canExport = ["admin", "operator"].includes(currentUser.role);
  const [query, setQuery] = useState("");
  const [clusterId, setClusterId] = useState("");
  const [status, setStatus] = useState("");
  const debouncedQuery = useDebouncedValue(query);
  const result = useCursorPage<RcaReport>(callApi, "/api/v1/rca/reports", {
    q: debouncedQuery,
    cluster_id: clusterId,
    status,
  }, refreshToken);
  const reports = result.page.items;
  return (
    <div className="page-stack">
      <PageHeader
        title={t("RCA Reports")}
        subtitle={t("Root cause candidates, evidence, policy gates, and operator workflow.")}
        actions={canExport && <button className="btn btn-sm btn-outline-secondary icon-button" onClick={onExportAll}><Icon name="download" /><span>{t("Export all")}</span></button>}
      />
      <div className="ops-filter-bar">
        <div className="input-group input-group-sm ops-search-control">
          <span className="input-group-text"><Icon name="search" /></span>
          <input className="form-control" value={query} onChange={(event) => setQuery(event.target.value)} placeholder={t("Search reports")} aria-label={t("Search reports")} />
        </div>
        <select className="form-select form-select-sm" value={clusterId} onChange={(event) => setClusterId(event.target.value)} aria-label={t("Filter by cluster")}>
          <option value="">{t("All clusters")}</option>
          {clusters.map((cluster) => <option key={cluster.cluster_id} value={cluster.cluster_id}>{cluster.name}</option>)}
        </select>
        <select className="form-select form-select-sm" value={status} onChange={(event) => setStatus(event.target.value)} aria-label={t("Filter by status")}>
          <option value="">{t("All statuses")}</option>
          <option value="completed">{t("completed")}</option>
          <option value="failed">{t("failed")}</option>
        </select>
      </div>
      {result.error && <div className="alert alert-warning py-2 mb-0">{result.error.detail}</div>}
      <div className="report-layout">
        <aside className="report-list">
          <div className="report-list-head">
            <span>{t("RCA Reports")}</span>
            <strong>{result.page.total}</strong>
          </div>
          <ReportListPanel reports={reports} selectedReportId={selectedReportId} onSelectReport={setSelectedReportId} t={t} />
          <CursorPager page={result.pageNumber} total={result.page.total} loading={result.loading} canPrevious={result.canPrevious} canNext={result.canNext} onPrevious={result.previous} onNext={result.next} t={t} />
        </aside>
        <section className="report-detail-panel">
          {detail?.report ? (
            <ReportDetail
              detail={detail}
              currentUser={currentUser}
              onPrepareAction={onPrepareAction}
              onDecideAction={onDecideAction}
              onCompleteManual={onCompleteManual}
              onExportReport={onExportReport}
              onExportBundle={onExportBundle}
              platformInfo={platformInfo}
              onCopy={onCopy}
              t={t}
            />
          ) : <EmptyState message={t("Select an RCA report.")} />}
        </section>
      </div>
    </div>
  );
}
