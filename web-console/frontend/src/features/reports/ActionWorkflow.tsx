// @ts-nocheck

import { useState } from "react";

import { EmptyState, Icon, MetricTile, StatusBadge, Surface } from "../../components/common";

import { policyTone, relativeTime, requestTone } from "../../lib/consoleUtils";

export function ActionList({ report, actions, onPrepareAction, t }) {
  if (!actions.length) return <EmptyState message="No recommended actions." />;
  return (
    <div className="action-grid">
      {actions.map((action, index) => {
        const automationBlocked = action.automation_allowed !== true;
        const llm = action.source === "llm";
        return (
          <article key={`${action.action_key}-${index}`} className={`action-card ${automationBlocked ? "blocked" : "allowed"}`}>
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
            {action.execution_plan?.command_preview?.length > 0 && (
              <pre className="command-preview">{action.execution_plan.command_preview.join("\n")}</pre>
            )}
            <button className="btn btn-sm btn-primary icon-button" onClick={() => onPrepareAction(report, action, index)}>
              <Icon name={action.automation_allowed ? "collection" : "person-check"} />
              <span>{action.automation_allowed ? t("Collect evidence") : t("Request action")}</span>
            </button>
          </article>
        );
      })}
    </div>
  );
}

export function ActionRequestList({ items, executions, currentUser, onDecideAction, onCompleteManual, t }) {
  const [noteById, setNoteById] = useState({});
  if (!items?.length) return <EmptyState message={t("No action requests.")} />;
  return (
    <div className="request-list">
      {items.map((item) => {
        const execution = (executions || []).find((value) => value.action_request_id === item.action_request_id);
        const canApprove = ["admin", "approver"].includes(currentUser.role) && item.status === "pending_approval";
        const canComplete = ["admin", "operator"].includes(currentUser.role) && item.status === "approved_manual";
        return (
          <article key={item.action_request_id} className="request-item">
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
                <input className="form-control form-control-sm" placeholder="Decision note" value={noteById[item.action_request_id] || ""} onChange={(event) => setNoteById({ ...noteById, [item.action_request_id]: event.target.value })} />
                {canApprove && <button className="btn btn-sm btn-success" onClick={() => onDecideAction(item, "approve", noteById[item.action_request_id] || "")}>{t("Approve")}</button>}
                {canApprove && <button className="btn btn-sm btn-outline-danger" onClick={() => onDecideAction(item, "reject", noteById[item.action_request_id] || "")}>{t("Reject")}</button>}
                {canComplete && <button className="btn btn-sm btn-primary" disabled={!noteById[item.action_request_id]} onClick={() => onCompleteManual(item, noteById[item.action_request_id])}>{t("Complete manual")}</button>}
              </div>
            )}
          </article>
        );
      })}
    </div>
  );
}
