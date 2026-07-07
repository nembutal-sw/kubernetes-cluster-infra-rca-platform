import { EmptyState, Icon, MetricTile } from "../../components/common";

import { formatDate, formatFreshness, qualityGateTone, qualityTone } from "../../lib/consoleUtils";
import type { TFunction } from "../../types";

type QualityRecord = Record<string, unknown>;

interface EvidenceQualityPanelProps {
  quality?: QualityRecord | null;
  gate?: QualityRecord | null;
  t: TFunction;
}

export function EvidenceQualityPanel({ quality, gate, t }: EvidenceQualityPanelProps) {
  if (!quality && !gate) return <EmptyState message={t("Report quality is not available for this report.")} />;
  const gateReasons = stringArray(gate?.reasons);
  const gateFollowUp = stringArray(gate?.follow_up || gate?.followUp);
  const safeQuality = quality || {};
  const freshness = recordValue(safeQuality.freshness);
  const collectorStatus = recordValue(safeQuality.collector_status || safeQuality.collectorStatus);
  const agentHealth = recordValue(safeQuality.agent_health || safeQuality.agentHealth);
  const notes = stringArray(safeQuality.notes);
  const expected = stringArray(collectorStatus.expected);
  const missing = stringArray(collectorStatus.missing);
  const failures = stringArray(collectorStatus.failures || collectorStatus.failed);
  const degraded = stringArray(collectorStatus.degraded);
  const confidencePenalty = Number(safeQuality.confidence_penalty ?? safeQuality.confidencePenalty ?? 0);
  return (
    <div className="evidence-quality-panel">
      {gate && (
        <div className={`quality-gate-card ${qualityGateTone(gate.status)}`}>
          <div>
            <span>{t("Quality gate")}</span>
            <strong>{String(gate.status || "unknown")}</strong>
            <small>{gate.rule_based_sufficient ? t("Rule-based RCA is usable") : t("Additional evidence is required")}</small>
          </div>
          <div className="quality-gate-stats">
            <div><span>{t("Rule signals")}</span><strong>{displayValue(gate.rule_signal_count, 0)}</strong></div>
            <div><span>{t("Top score")}</span><strong>{displayValue(gate.top_candidate_score, "n/a")}</strong></div>
            <div><span>{t("Penalty")}</span><strong>{displayValue(gate.confidence_penalty, 0)}</strong></div>
            <div><span>{t("LLM")}</span><strong>{gate.llm_should_not_raise_confidence ? t("Diagnostic only") : t("Assistive")}</strong></div>
          </div>
          {(gateReasons.length > 0 || gateFollowUp.length > 0) && (
            <div className="quality-gate-notes">
              {[...gateReasons, ...gateFollowUp].slice(0, 6).map((item, index) => <span key={`${item}-${index}`}>{item}</span>)}
            </div>
          )}
        </div>
      )}
      <div className="quality-metric-grid">
        <MetricTile label={t("Quality status")} value={String(quality?.status || "unknown")} tone={qualityTone(quality?.status)} icon="clipboard2-pulse" />
        <MetricTile label={t("Quality score")} value={displayValue(quality?.quality_score ?? quality?.qualityScore, "n/a")} tone={qualityTone(quality?.status)} icon="speedometer2" />
        <MetricTile label={t("Confidence penalty")} value={confidencePenalty} tone={confidencePenalty > 0 ? "amber" : "green"} icon="shield-exclamation" />
        <MetricTile label={t("Agent health")} value={String(agentHealth.status || "unknown")} tone={qualityTone(agentHealth.status)} icon="hdd-network" />
      </div>
      <div className="quality-detail-grid">
        <article>
          <span>{t("Freshness")}</span>
          <strong>{formatFreshness(freshness)}</strong>
          <small>{freshness.collected_at ? `${t("Collected at")}: ${formatDate(freshness.collected_at)}` : t("No collection timestamp")}</small>
        </article>
        <article>
          <span>{t("Collector coverage")}</span>
          <strong>{expected.length ? `${Math.max(0, expected.length - missing.length)}/${expected.length}` : "n/a"}</strong>
          <small>{missing.length ? `${t("Missing")}: ${missing.slice(0, 4).join(", ")}` : t("Expected collectors reported")}</small>
        </article>
        <article>
          <span>{t("Collector status")}</span>
          <strong>{String(collectorStatus.status || "unknown")}</strong>
          <small>{[...failures, ...degraded].slice(0, 4).join(", ") || t("No collector degradation reported")}</small>
        </article>
        <article>
          <span>{t("Agent detail")}</span>
          <strong>{String(agentHealth.node_name || agentHealth.nodeName || "cluster scope")}</strong>
          <small>{agentHealth.last_heartbeat_at ? `${t("Last heartbeat")}: ${formatDate(agentHealth.last_heartbeat_at)}` : t("No heartbeat data")}</small>
        </article>
      </div>
      {notes.length > 0 && (
        <div className="quality-note-list">
          {notes.slice(0, 6).map((note, index) => <span key={`${note}-${index}`}><Icon name="exclamation-triangle" />{note}</span>)}
        </div>
      )}
    </div>
  );
}

function recordValue(value: unknown): QualityRecord {
  return value !== null && typeof value === "object" && !Array.isArray(value) ? value as QualityRecord : {};
}

function stringArray(value: unknown): string[] {
  return Array.isArray(value) ? value.map((item) => String(item)) : [];
}

function displayValue(value: unknown, fallback: string | number): string | number {
  if (value === null || value === undefined || value === "") return fallback;
  if (typeof value === "number" || typeof value === "string") return value;
  return String(value);
}
