// @ts-nocheck

import { POLICY_HELP } from "../../constants";

import { EmptyState, Icon, MetricTile, StatusBadge, Surface } from "../../components/common";

import { confidenceTone, formatPercentValue, policyTone, severityTone, shortValue, signalIcon } from "../../lib/consoleUtils";

export function CandidateList({ candidates, t }) {
  if (!candidates.length) return <EmptyState message={t("No root cause candidates.")} />;
  return (
    <div className="candidate-list">
      {candidates.map((candidate, index) => (
        <article key={`${candidate.cause}-${index}`} className="candidate-item">
          <div className="candidate-score" style={{ "--score": `${candidate.confidence_score || 0}%` }}>
            <strong>{candidate.confidence_score || 0}</strong>
            <span>%</span>
          </div>
          <div>
            <strong>{candidate.cause}</strong>
            <div className="supporting-lines">
              {(candidate.supporting_evidence || []).slice(0, 3).map((line) => <span key={line}>{line}</span>)}
            </div>
            {(candidate.evidence_paths || []).length > 0 && (
              <div className="path-list" aria-label={t("Evidence paths")}>
                {(candidate.evidence_paths || []).slice(0, 5).map((path) => <code key={path}>{path}</code>)}
              </div>
            )}
          </div>
        </article>
      ))}
    </div>
  );
}

export function RuleEvidencePanel({ signals, t }) {
  if (!signals.length) return <EmptyState message={t("No derived rule signals.")} />;
  return (
    <div className="rule-signal-grid">
      {signals.slice(0, 12).map((signal, index) => {
        const matchedFields = signal.matched_fields || signal.matchedFields || [];
        const supportingEvidence = signal.supporting_evidence || signal.supportingEvidence || [];
        return (
          <article key={`${signal.signal || signal.name}-${index}`} className={`rule-signal-card ${severityTone(signal.severity)}`}>
            <header>
              <div>
                <p className="section-kicker">{signal.component || "component"}</p>
                <h3>{signal.signal || signal.name || "signal"}</h3>
              </div>
              <div className="signal-badges">
                <StatusBadge value={signal.severity || "info"} tone={severityTone(signal.severity)} t={t} />
                <StatusBadge value={signal.confidence || "unknown"} tone={confidenceTone(signal.confidence)} t={t} />
              </div>
            </header>
            <p>{signal.interpretation || "No interpretation available."}</p>
            <div className="rule-value-grid">
              <div><span>{t("Observed")}</span><strong>{shortValue(signal.observed)}</strong></div>
              <div><span>{t("Threshold")}</span><strong>{signal.threshold === undefined || signal.threshold === null ? "n/a" : shortValue(signal.threshold)}</strong></div>
            </div>
            {matchedFields.length > 0 && (
              <div className="path-list">
                <span>{t("Matched fields")}</span>
                {matchedFields.slice(0, 6).map((field) => <code key={field}>{field}</code>)}
              </div>
            )}
            {supportingEvidence.length > 0 && (
              <div className="supporting-lines">
                {supportingEvidence.slice(0, 3).map((line) => <span key={line}>{line}</span>)}
              </div>
            )}
            {signal.next_step && (
              <div className="next-step">
                <span>{t("Next step")}</span>
                <strong>{signal.next_step}</strong>
              </div>
            )}
          </article>
        );
      })}
    </div>
  );
}

export function EvidenceSummary({ items, t = (x) => x }) {
  if (!items.length) return <EmptyState message={t("No evidence summary.")} />;
  return (
    <div className="signal-list compact">
      {items.slice(0, 10).map((item, index) => (
        <article key={`${item.label}-${index}`} className="signal-row">
          <Icon name={signalIcon(item.label)} />
          <div>
            <strong>{item.label}</strong>
            <span>{item.value}</span>
          </div>
        </article>
      ))}
    </div>
  );
}

export function CheckList({ checks, t = (x) => x }) {
  const normalized = Array.isArray(checks) ? checks : [];
  if (!normalized.length) return <EmptyState message={t("No additional checks.")} />;
  return (
    <div className="command-list">
      {normalized.map((item, index) => {
        const command = typeof item === "string" ? item : item.command || item.description || JSON.stringify(item);
        return <pre key={`${command}-${index}`}>{command}</pre>;
      })}
    </div>
  );
}

export function PolicySummary({ actions, t }) {
  if (!actions.length) return <EmptyState message={t("No policy decisions.")} />;
  const counts = actions.reduce((acc, action) => {
    const key = action.policy || "UNKNOWN";
    acc[key] = (acc[key] || 0) + 1;
    return acc;
  }, {});
  return (
    <div className="policy-grid">
      {Object.entries(counts).map(([policy, count]) => (
        <article key={policy} className={`policy-tile ${policyTone(policy)}`}>
          <StatusBadge value={policy} tone={policyTone(policy)} t={t} />
          <strong>{count}</strong>
          <span>{t(POLICY_HELP[policy] || "Policy decision")}</span>
        </article>
      ))}
    </div>
  );
}
