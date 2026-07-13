import { POLICY_HELP } from "../../constants";

import type { CSSProperties } from "react";

import { EmptyState, Icon, StatusBadge } from "../../components/common";

import { confidenceTone, policyTone, severityTone, shortValue, signalIcon } from "../../lib/consoleUtils";
import type { RecommendedAction, RootCauseCandidate, TFunction } from "../../types";

interface CandidateListProps {
  candidates: RootCauseCandidate[];
  t: TFunction;
}

interface RuleEvidencePanelProps {
  signals: unknown[];
  t: TFunction;
}

interface EvidenceSummaryItem {
  label: string;
  value: string;
}

interface EvidenceSummaryProps {
  items: EvidenceSummaryItem[];
  t?: TFunction;
}

interface CheckListProps {
  checks: unknown[];
  t?: TFunction;
}

interface PolicySummaryProps {
  actions: RecommendedAction[];
  t: TFunction;
}

interface LlmUsagePanelProps {
  analysis: Record<string, unknown>;
  t: TFunction;
}

type ScoreStyle = CSSProperties & { "--score": string };

export function CandidateList({ candidates, t }: CandidateListProps) {
  if (!candidates.length) return <EmptyState message={t("No root cause candidates.")} />;
  return (
    <div className="candidate-list">
      {candidates.map((candidate, index) => {
        const score = candidate.confidence_score || 0;
        const evidenceIds = candidate.supporting_evidence_ids || [];
        const scoreStyle: ScoreStyle = { "--score": `${score}%` };
        return (
        <article key={`${candidate.cause || candidate.reason}-${index}`} className="candidate-item">
          <div className="candidate-score" style={scoreStyle}>
            <strong>{score}</strong>
            <span>%</span>
          </div>
          <div>
            <strong>{candidate.cause || candidate.reason || t("Unknown candidate")}</strong>
            <div className="supporting-lines">
              {(candidate.supporting_evidence || []).slice(0, 3).map((line) => <span key={line}>{line}</span>)}
            </div>
            {evidenceIds.length > 0 && (
              <div className="evidence-id-list" aria-label={t("Evidence IDs")}>
                <span>{t("Evidence IDs")}</span>
                {evidenceIds.slice(0, 10).map((evidenceId) => <code key={evidenceId}>{evidenceId}</code>)}
              </div>
            )}
            {(candidate.evidence_paths || []).length > 0 && (
              <div className="path-list" aria-label={t("Evidence paths")}>
                {(candidate.evidence_paths || []).slice(0, 5).map((path) => <code key={path}>{path}</code>)}
              </div>
            )}
          </div>
        </article>
      );
      })}
    </div>
  );
}

export function RuleEvidencePanel({ signals, t }: RuleEvidencePanelProps) {
  if (!signals.length) return <EmptyState message={t("No derived rule signals.")} />;
  return (
    <div className="rule-signal-grid">
      {signals.slice(0, 12).map((signal, index) => {
        const signalRecord = recordValue(signal);
        const matchedFields = stringArray(signalRecord.matched_fields || signalRecord.matchedFields);
        const supportingEvidence = stringArray(signalRecord.supporting_evidence || signalRecord.supportingEvidence);
        const signalName = String(signalRecord.signal || signalRecord.name || "signal");
        const component = String(signalRecord.component || "component");
        const severity = signalRecord.severity;
        const confidence = signalRecord.confidence;
        return (
          <article key={`${signalName}-${index}`} className={`rule-signal-card ${severityTone(severity)}`}>
            <header>
              <div>
                <p className="section-kicker">{component}</p>
                <h3>{signalName}</h3>
              </div>
              <div className="signal-badges">
                <StatusBadge value={severity || "info"} tone={severityTone(severity)} t={t} />
                <StatusBadge value={confidence || "unknown"} tone={confidenceTone(confidence)} t={t} />
              </div>
            </header>
            <p>{String(signalRecord.interpretation || "No interpretation available.")}</p>
            <div className="rule-value-grid">
              <div><span>{t("Observed")}</span><strong>{shortValue(signalRecord.observed)}</strong></div>
              <div><span>{t("Threshold")}</span><strong>{signalRecord.threshold === undefined || signalRecord.threshold === null ? "n/a" : shortValue(signalRecord.threshold)}</strong></div>
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
            {Boolean(signalRecord.next_step) && (
              <div className="next-step">
                <span>{t("Next step")}</span>
                <strong>{String(signalRecord.next_step)}</strong>
              </div>
            )}
          </article>
        );
      })}
    </div>
  );
}

export function EvidenceSummary({ items, t = (x) => x }: EvidenceSummaryProps) {
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

export function CheckList({ checks, t = (x) => x }: CheckListProps) {
  const normalized = Array.isArray(checks) ? checks : [];
  if (!normalized.length) return <EmptyState message={t("No additional checks.")} />;
  return (
    <div className="command-list">
      {normalized.map((item, index) => {
        const record = recordValue(item);
        const command = typeof item === "string" ? item : String(record.command || record.description || JSON.stringify(item));
        return <pre key={`${command}-${index}`}>{command}</pre>;
      })}
    </div>
  );
}

export function PolicySummary({ actions, t }: PolicySummaryProps) {
  if (!actions.length) return <EmptyState message={t("No policy decisions.")} />;
  const counts = actions.reduce<Record<string, number>>((acc, action) => {
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

export function LlmUsagePanel({ analysis, t }: LlmUsagePanelProps) {
  const usage = recordValue(analysis.usage);
  const available = Boolean(usage.usage_available ?? usage.usageAvailable);
  const costEnabled = Boolean(usage.cost_estimation_enabled ?? usage.costEstimationEnabled);
  const inputTokens = Number(usage.input_tokens ?? usage.inputTokens ?? 0);
  const outputTokens = Number(usage.output_tokens ?? usage.outputTokens ?? 0);
  const totalTokens = Number(usage.total_tokens ?? usage.totalTokens ?? 0);
  const estimatedCost = Number(usage.estimated_cost_usd ?? usage.estimatedCostUsd ?? 0);
  const latencyMs = Number(analysis.latency_ms ?? analysis.latencyMs ?? 0);
  return (
    <div className="llm-diagnostics">
      <div className="signal-badges">
        <StatusBadge value={analysis.status || "unknown"} tone={analysis.status === "completed" ? "ok" : "muted"} t={t} />
        <span>{String(analysis.provider || "none")}</span>
        <span>{String(analysis.model || "n/a")}</span>
        <code>{String(analysis.prompt_version || analysis.promptVersion || "n/a")}</code>
      </div>
      <div className="rule-value-grid">
        <div><span>{t("Latency")}</span><strong>{latencyMs > 0 ? `${latencyMs.toLocaleString()} ms` : "n/a"}</strong></div>
        <div><span>{t("Input tokens")}</span><strong>{available ? inputTokens.toLocaleString() : "n/a"}</strong></div>
        <div><span>{t("Output tokens")}</span><strong>{available ? outputTokens.toLocaleString() : "n/a"}</strong></div>
        <div><span>{t("Total tokens")}</span><strong>{available ? totalTokens.toLocaleString() : "n/a"}</strong></div>
        <div><span>{t("Estimated cost")}</span><strong>{!costEnabled ? t("Disabled") : available ? `$${estimatedCost.toFixed(8)}` : "n/a"}</strong></div>
      </div>
      {!available && analysis.status === "completed" && (
        <div className="next-step"><span>{t("Usage metadata")}</span><strong>{t("Provider did not return token usage metadata.")}</strong></div>
      )}
    </div>
  );
}

function recordValue(value: unknown): Record<string, unknown> {
  return value !== null && typeof value === "object" && !Array.isArray(value) ? value as Record<string, unknown> : {};
}

function stringArray(value: unknown): string[] {
  return Array.isArray(value) ? value.map((item) => String(item)) : [];
}
