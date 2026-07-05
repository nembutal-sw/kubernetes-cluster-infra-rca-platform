// @ts-nocheck

import { EmptyState, Icon, MetricTile, StatusBadge, Surface } from "../../components/common";

import { formatDate, formatFreshness, qualityGateTone, qualityTone } from "../../lib/consoleUtils";

export function EvidenceQualityPanel({ quality, gate, t }) {
  if (!quality && !gate) return <EmptyState message={t("Report quality is not available for this report.")} />;
  const gateReasons = Array.isArray(gate?.reasons) ? gate.reasons : [];
  const gateFollowUp = Array.isArray(gate?.follow_up || gate?.followUp) ? (gate.follow_up || gate.followUp) : [];
  const safeQuality = quality || {};
  const freshness = safeQuality.freshness || {};
  const collectorStatus = safeQuality.collector_status || safeQuality.collectorStatus || {};
  const agentHealth = safeQuality.agent_health || safeQuality.agentHealth || {};
  const notes = Array.isArray(safeQuality.notes) ? safeQuality.notes : [];
  const expected = collectorStatus.expected || [];
  const missing = collectorStatus.missing || [];
  const failures = collectorStatus.failures || collectorStatus.failed || [];
  const degraded = collectorStatus.degraded || [];
  return (
    <div className="evidence-quality-panel">
      {gate && (
        <div className={`quality-gate-card ${qualityGateTone(gate.status)}`}>
          <div>
            <span>{t("Quality gate")}</span>
            <strong>{gate.status || "unknown"}</strong>
            <small>{gate.rule_based_sufficient ? t("Rule-based RCA is usable") : t("Additional evidence is required")}</small>
          </div>
          <div className="quality-gate-stats">
            <div><span>{t("Rule signals")}</span><strong>{gate.rule_signal_count ?? 0}</strong></div>
            <div><span>{t("Top score")}</span><strong>{gate.top_candidate_score ?? "n/a"}</strong></div>
            <div><span>{t("Penalty")}</span><strong>{gate.confidence_penalty ?? 0}</strong></div>
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
        <MetricTile label={t("Quality status")} value={quality?.status || "unknown"} tone={qualityTone(quality?.status)} icon="clipboard2-pulse" />
        <MetricTile label={t("Quality score")} value={quality?.quality_score ?? quality?.qualityScore ?? "n/a"} tone={qualityTone(quality?.status)} icon="speedometer2" />
        <MetricTile label={t("Confidence penalty")} value={quality?.confidence_penalty ?? quality?.confidencePenalty ?? 0} tone={(quality?.confidence_penalty ?? quality?.confidencePenalty ?? 0) > 0 ? "amber" : "green"} icon="shield-exclamation" />
        <MetricTile label={t("Agent health")} value={agentHealth.status || "unknown"} tone={qualityTone(agentHealth.status)} icon="hdd-network" />
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
          <strong>{collectorStatus.status || "unknown"}</strong>
          <small>{[...failures, ...degraded].slice(0, 4).join(", ") || t("No collector degradation reported")}</small>
        </article>
        <article>
          <span>{t("Agent detail")}</span>
          <strong>{agentHealth.node_name || agentHealth.nodeName || "cluster scope"}</strong>
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
