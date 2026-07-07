import { EmptyState } from "../../components/common";
import { relativeTime } from "../../lib/consoleUtils";
import type { RcaReport, TFunction } from "../../types";

interface ReportListPanelProps {
  reports: RcaReport[];
  selectedReportId: string | null;
  onSelectReport: (reportId: string) => void;
  t: TFunction;
}

export function ReportListPanel({ reports, selectedReportId, onSelectReport, t }: ReportListPanelProps) {
  if (!reports.length) return <EmptyState message={t("No reports loaded.")} />;
  return (
    <>
      {reports.map((report) => (
        <button
          key={report.report_id}
          className={selectedReportId === report.report_id ? "selected" : ""}
          onClick={() => onSelectReport(report.report_id)}
        >
          <span className="report-time">{relativeTime(report.created_at)}</span>
          <strong>{report.summary?.symptom || report.trigger?.alert_name || report.report_id}</strong>
          <span>{report.cluster_id} / {report.node_name || "cluster"}</span>
        </button>
      ))}
    </>
  );
}
