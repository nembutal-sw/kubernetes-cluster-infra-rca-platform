import { EmptyState, Icon, PageHeader } from "../components/common";
import { ReportDetail } from "../features/reports/ReportDetail";
import { ReportListPanel } from "../features/reports/ReportListPanel";
import type {
  ActionRequestView,
  PlatformInfo,
  RcaReport,
  RecommendedAction,
  ReportDetailState,
  TFunction,
  UserAccount,
} from "../types";

interface ReportsViewProps {
  reports: RcaReport[];
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

export function ReportsView({ reports, selectedReportId, setSelectedReportId, detail, currentUser, onPrepareAction, onDecideAction, onCompleteManual, onExportReport, onExportBundle, onExportAll, platformInfo, onCopy, t }: ReportsViewProps) {
  const canExport = ["admin", "operator"].includes(currentUser.role);
  return (
    <div className="page-stack">
      <PageHeader
        title={t("RCA Reports")}
        subtitle={t("Root cause candidates, evidence, policy gates, and operator workflow.")}
        actions={canExport && <button className="btn btn-sm btn-outline-secondary icon-button" onClick={onExportAll}><Icon name="download" /><span>{t("Export all")}</span></button>}
      />
      <div className="report-layout">
        <aside className="report-list">
          <ReportListPanel reports={reports} selectedReportId={selectedReportId} onSelectReport={setSelectedReportId} t={t} />
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
