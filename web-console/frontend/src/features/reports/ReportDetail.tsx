import { Icon, MetricTile, Surface } from "../../components/common";

import { confidenceTone, derivedSignals, evidenceSummary, formatDate, qualityGateTone, qualityTone, reportEvidenceQuality, reportQualityGate } from "../../lib/consoleUtils";
import type { ActionRequestView, PlatformInfo, RcaReport, RecommendedAction, ReportDetailState, TFunction, UserAccount } from "../../types";

import { EvidenceQualityPanel } from "./EvidenceQualityPanel";

import { BundleVerificationPanel } from "./BundleVerificationPanel";

import { CandidateList, CheckList, EvidenceSummary, PolicySummary, RuleEvidencePanel } from "./ReportEvidencePanels";

import { ActionList, ActionRequestList } from "./ActionWorkflow";

import { TimelineGraph } from "./TimelineGraph";

interface ReportDetailProps {
  detail: ReportDetailState;
  currentUser: UserAccount;
  onPrepareAction: (report: RcaReport, action: RecommendedAction, index: number) => void;
  onDecideAction: (actionRequest: ActionRequestView, decision: "approve" | "reject", note?: string) => Promise<void> | void;
  onCompleteManual: (actionRequest: ActionRequestView, note: string) => Promise<void> | void;
  onExportReport: (reportId: string) => Promise<void> | void;
  onExportBundle: (reportId: string) => Promise<void> | void;
  platformInfo?: PlatformInfo | null;
  onCopy: (text: string) => void;
  t: TFunction;
}

export function ReportDetail({ detail, currentUser, onPrepareAction, onDecideAction, onCompleteManual, onExportReport, onExportBundle, platformInfo, onCopy, t }: ReportDetailProps) {
  const report = detail.report;
  const candidates = report.root_cause_candidates || [];
  const actions = report.recommended_actions || [];
  const checks = report.additional_checks || report.next_steps || [];
  const evidenceItems = evidenceSummary(report);
  const signals = derivedSignals(report);
  const quality = recordOrNull(reportEvidenceQuality(report));
  const gate = recordOrNull(reportQualityGate(report));
  const llmActions = actions.filter((action) => action.source === "llm");
  const canExport = ["admin", "operator"].includes(currentUser.role);
  const exportSecurity = recordValue(platformInfo?.export_security || platformInfo?.exportSecurity);
  const bundleSignatureEnabled = Boolean(exportSecurity.bundle_signature_enabled ?? exportSecurity.bundleSignatureEnabled);
  return (
    <div className="report-detail">
      <div className="report-detail-head">
        <div>
          <p className="section-kicker">{report.report_id}</p>
          <h2>{report.summary?.most_likely_cause || report.summary?.symptom || "RCA report"}</h2>
          <div className="meta-row">
            <span>{report.cluster_id}</span>
            <span>{report.node_name || "cluster scope"}</span>
            <span>{formatDate(report.created_at)}</span>
          </div>
        </div>
        {canExport && (
          <div className="report-export-actions">
            <button className="btn btn-sm btn-outline-secondary icon-button" onClick={() => onExportReport(report.report_id)}>
              <Icon name="download" /><span>{t("Export report")}</span>
            </button>
            <button className="btn btn-sm btn-outline-secondary icon-button" onClick={() => onExportBundle(report.report_id)}>
              <Icon name="file-earmark-zip" /><span>{t("Download bundle")}</span>
            </button>
            <span className={`bundle-signature-pill ${bundleSignatureEnabled ? "signed" : "unsigned"}`}>
              <Icon name={bundleSignatureEnabled ? "shield-check" : "shield-slash"} />
              <span>{bundleSignatureEnabled ? t("Signed bundle") : t("Unsigned bundle")}</span>
            </span>
          </div>
        )}
      </div>

      <div className="summary-strip">
        <MetricTile label="Confidence" value={report.summary?.confidence || "n/a"} tone={confidenceTone(report.summary?.confidence)} icon="bar-chart-line" />
        <MetricTile label={t("Rule signals")} value={signals.length} tone={signals.length ? "blue" : "muted"} icon="diagram-3" />
        <MetricTile label={t("Quality gate")} value={String(gate?.status || "unknown")} tone={qualityGateTone(gate?.status)} icon="shield-check" />
        <MetricTile label={t("Evidence quality")} value={String(quality?.status || "unknown")} tone={qualityTone(quality?.status)} icon="clipboard2-pulse" />
        <MetricTile label={t("Policy blocked")} value={actions.filter((action) => !action.automation_allowed).length} tone="amber" icon="shield-lock" />
        <MetricTile label="LLM" value={llmActions.length ? t("LLM diagnostic only") : "n/a"} tone={llmActions.length ? "amber" : "muted"} icon="stars" />
      </div>

      {llmActions.length > 0 && (
        <div className="policy-warning">
          <Icon name="shield-exclamation" />
          <span>{t("LLM diagnostic only")}: {t("LLM-origin actions stay automation_allowed=false. Operators can create a request, record approval/rejection, or mark manual handling complete.")}</span>
        </div>
      )}

      <Surface title={t("Report quality")} subtitle={t("Rule signal sufficiency, freshness, collector coverage, and confidence gate")}>
        <EvidenceQualityPanel quality={quality} gate={gate} t={t} />
      </Surface>

      {canExport && (
        <Surface title={t("Bundle verification")} subtitle={t("Offline integrity check and current manifest preview")}>
          <BundleVerificationPanel manifest={detail.bundleManifest} platformInfo={platformInfo} onCopy={onCopy} t={t} />
        </Surface>
      )}

      <Surface title={t("Cascading timeline")} subtitle={t("Observed evidence order and inferred propagation")}>
        <TimelineGraph timeline={detail.timeline} report={report} t={t} />
      </Surface>

      <Surface title={t("Rule evidence")} subtitle={t("Rule-based detector output before LLM analysis")}>
        <RuleEvidencePanel signals={signals} t={t} />
      </Surface>

      <div className="detail-grid">
        <Surface title={t("Root cause candidates")} subtitle={t("Ranked by rule and evidence confidence")}>
          <CandidateList candidates={candidates} t={t} />
        </Surface>
        <Surface title={t("Evidence summary")} subtitle={t("Signals used by analyzer")}>
          <EvidenceSummary items={evidenceItems} t={t} />
        </Surface>
        <Surface title={t("Additional checks")} subtitle={t("Commands to verify before remediation")}>
          <CheckList checks={checks} t={t} />
        </Surface>
        <Surface title={t("Policy gate")} subtitle={t("Policy Engine result before any action request")}>
          <PolicySummary actions={actions} t={t} />
        </Surface>
      </div>

      <Surface title={t("Recommended actions")} subtitle={t("Every action remains behind policy and human confirmation")}>
        <ActionList report={report} actions={actions} onPrepareAction={onPrepareAction} t={t} />
      </Surface>

      <Surface title={t("Action requests")} subtitle={t("Approval records and manual completion")}>
        <ActionRequestList
          items={detail.actionRequests}
          executions={detail.actionExecutions}
          currentUser={currentUser}
          onDecideAction={onDecideAction}
          onCompleteManual={onCompleteManual}
          t={t}
        />
      </Surface>
    </div>
  );
}

function recordOrNull(value: unknown): Record<string, unknown> | null {
  return value !== null && typeof value === "object" && !Array.isArray(value) ? value as Record<string, unknown> : null;
}

function recordValue(value: unknown): Record<string, unknown> {
  return recordOrNull(value) || {};
}
