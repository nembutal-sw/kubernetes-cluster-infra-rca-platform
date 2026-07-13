import { useState } from "react";

import { EmptyState, Icon, StatusBadge } from "../../components/common";

import { policyTone, relativeTime, requestTone } from "../../lib/consoleUtils";
import type { ActionExecutionView, ActionRequestView, RcaReport, RecommendedAction, TFunction, UserAccount } from "../../types";

interface ActionListProps {
  report: RcaReport;
  actions: RecommendedAction[];
  onPrepareAction: (report: RcaReport, action: RecommendedAction, index: number) => void;
  t: TFunction;
}

interface ActionRequestListProps {
  items?: ActionRequestView[];
  executions?: ActionExecutionView[];
  currentUser: UserAccount;
  onDecideAction: (actionRequest: ActionRequestView, decision: "approve" | "reject", note?: string) => Promise<void> | void;
  onCompleteManual: (actionRequest: ActionRequestView, note: string) => Promise<void> | void;
  t: TFunction;
}

export function ActionList({ report, actions, onPrepareAction, t }: ActionListProps) {
  if (!actions.length) return <EmptyState message={t("No recommended actions.")} />;
  return (
    <div className="action-grid">
      {actions.map((action, index) => {
        const automationBlocked = action.automation_allowed !== true;
        const llm = action.source === "llm";
        const commandPreview = action.execution_plan?.command_preview || [];
        return (
          <article key={`${action.action_key}-${index}`} className={`action-card ${automationBlocked ? "blocked" : "allowed"}`} data-testid="recommended-action" data-action-index={index}>
            <div className="action-head">
              <StatusBadge value={action.policy} tone={policyTone(action.policy)} t={t} />
              {llm && <span className="llm-pill">{t("LLM diagnostic only")}</span>}
            </div>
            <h3>{action.action}</h3>
            <p>{action.reason}</p>
            <div className="action-meta">
              <span>{t("Automation")}</span>
              <strong>{automationBlocked ? t("Automation blocked") : "read-only collection"}</strong>
            </div>
            {(action.risk_factors || []).length > 0 && (
              <div className="risk-list">
                {(action.risk_factors || []).slice(0, 3).map((risk) => <span key={risk}>{risk}</span>)}
              </div>
            )}
            {commandPreview.length > 0 && (
              <pre className="command-preview">{commandPreview.join("\n")}</pre>
            )}
            <button className="btn btn-sm btn-primary icon-button" data-testid="request-action" onClick={() => onPrepareAction(report, action, index)}>
              <Icon name={action.automation_allowed ? "collection" : "person-check"} />
              <span>{action.automation_allowed ? t("Collect evidence") : t("Request action")}</span>
            </button>
          </article>
        );
      })}
    </div>
  );
}

export function ActionRequestList({ items, executions, currentUser, onDecideAction, onCompleteManual, t }: ActionRequestListProps) {
  const [noteById, setNoteById] = useState<Record<string, string>>({});
  if (!items?.length) return <EmptyState message={t("No action requests.")} />;
  return (
    <div className="request-list">
      {items.map((item) => {
        const execution = (executions || []).find((value) => value.action_request_id === item.action_request_id);
        const canApprove = ["admin", "approver"].includes(currentUser.role) && item.status === "pending_approval";
        const canComplete = ["admin", "operator"].includes(currentUser.role) && item.status === "approved_manual";
        const note = noteById[item.action_request_id] || "";
        return (
          <article key={item.action_request_id} className="request-item" data-testid="action-request" data-request-id={item.action_request_id}>
            <div>
              <strong>{item.action_key}</strong>
              <span>{item.action_request_id}</span>
            </div>
            <StatusBadge value={item.status} tone={requestTone(item.status)} t={t} />
            <div className="request-meta">
              <span>{item.policy}</span>
              <span>{item.source}</span>
              <span>{relativeTime(item.created_at)}</span>
            </div>
            {execution && <pre className="command-preview">{execution.status}: {execution.command_key}</pre>}
            {(canApprove || canComplete) && (
              <div className="request-actions">
                <input className="form-control form-control-sm" data-testid="action-decision-note" placeholder={t("Decision note")} value={note} onChange={(event) => setNoteById({ ...noteById, [item.action_request_id]: event.target.value })} />
                {canApprove && <button className="btn btn-sm btn-success" data-testid="action-approve" onClick={() => onDecideAction(item, "approve", note)}>{t("Approve")}</button>}
                {canApprove && <button className="btn btn-sm btn-outline-danger" data-testid="action-reject" onClick={() => onDecideAction(item, "reject", note)}>{t("Reject")}</button>}
                {canComplete && <button className="btn btn-sm btn-primary" data-testid="action-complete-manual" disabled={!note} onClick={() => onCompleteManual(item, note)}>{t("Complete manual")}</button>}
              </div>
            )}
          </article>
        );
      })}
    </div>
  );
}
