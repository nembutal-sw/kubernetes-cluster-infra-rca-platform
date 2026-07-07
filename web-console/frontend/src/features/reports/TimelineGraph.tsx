import { useState } from "react";

import { EmptyState, Icon, StatusBadge } from "../../components/common";

import {
  derivedSignals,
  fallbackTimeline,
  formatDate,
  formatPercentValue,
  qualityTone,
  severityTone,
  signalIcon,
} from "../../lib/consoleUtils";
import type { RcaReport, TFunction } from "../../types";

type TimelineRecord = Record<string, unknown>;

interface TimelineGraphProps {
  timeline?: unknown;
  report: RcaReport;
  t?: TFunction;
}

interface TimelineNodeDetailProps {
  node?: TimelineRecord | null;
  incoming?: TimelineRecord | null;
  source?: TimelineRecord | null;
  report: RcaReport;
  t: TFunction;
}

export function TimelineGraph({ timeline, report, t = (x) => x }: TimelineGraphProps) {
  const timelineRecord = recordValue(timeline);
  const providedNodes = arrayRecords(timelineRecord.nodes);
  const nodes = providedNodes.length ? providedNodes : fallbackTimeline(report).map(recordValue);
  const [selectedNodeId, setSelectedNodeId] = useState<string | null>(null);
  if (!nodes.length) return <EmptyState message={t("Timeline evidence is not available.")} />;

  const edges = arrayRecords(timelineRecord.edges);
  const summary = recordValue(timelineRecord.summary);
  const edgeByTarget = new Map(edges.map((edge) => [stringValue(edge.target), edge]));
  const nodeById = new Map(nodes.map((node, index) => [nodeKey(node, index), node]));
  const rootNode = nodes.find((node) => Boolean(node.root_trigger || node.rootTrigger))
    || nodeById.get(stringValue(summary.root_node_id))
    || null;
  const selectedNode = selectedNodeId ? nodeById.get(selectedNodeId) : null;
  const activeNode = selectedNode || rootNode || nodes[0];
  const activeNodeId = activeNode ? nodeKey(activeNode, nodes.indexOf(activeNode)) : "";
  const selectedIncoming = activeNode ? edgeByTarget.get(activeNodeId) || null : null;
  const selectedSource = selectedIncoming ? nodeById.get(stringValue(selectedIncoming.source)) || null : null;

  return (
    <>
      <div className="timeline-summary-bar">
        <article>
          <span>{t("Root trigger")}</span>
          <strong>{stringValue(rootNode?.title) || stringValue(summary.root_title) || "unknown"}</strong>
        </article>
        <article>
          <span>{t("Causal edges")}</span>
          <strong>{displayValue(summary.causal_edge_count, edges.filter((edge) => Boolean(edge.inferred)).length)}</strong>
        </article>
        <article>
          <span>{t("Observed edges")}</span>
          <strong>{displayValue(summary.temporal_edge_count, edges.filter((edge) => !edge.inferred).length)}</strong>
        </article>
        <article>
          <span>{t("Evidence quality")}</span>
          <StatusBadge value={summary.quality_status || "unknown"} tone={qualityTone(summary.quality_status)} t={t} />
        </article>
      </div>
      <div className="timeline-graph enhanced">
        {nodes.slice(0, 12).map((node, index) => {
          const id = nodeKey(node, index);
          const root = Boolean(node.root_trigger || node.rootTrigger);
          const incoming = edgeByTarget.get(id) || null;
          const source = incoming ? nodeById.get(stringValue(incoming.source)) || null : null;
          const evidencePaths = stringArray(node.evidence_paths || node.evidencePaths);
          const quality = recordValue(node.evidence_quality || node.evidenceQuality);
          const qualityStatus = stringValue(quality.status || quality.evidence_status || quality.evidenceStatus);
          const score = node.root_cause_score ?? node.rootCauseScore;
          const selected = activeNodeId === id;
          return (
            <article
              key={id}
              className={`${root ? "root" : ""} ${incoming?.inferred ? "inferred" : "observed"} ${selected ? "selected" : ""}`}
              role="button"
              tabIndex={0}
              onClick={() => setSelectedNodeId(id)}
              onKeyDown={(event) => {
                if (event.key === "Enter" || event.key === " ") {
                  event.preventDefault();
                  setSelectedNodeId(id);
                }
              }}
            >
              <div className="timeline-card-head">
                <div className="timeline-dot"><Icon name={root ? "bullseye" : signalIcon(node.component || node.signal_family || node.signalFamily)} /></div>
                <div className="timeline-badges">
                  {root && <StatusBadge value={t("Root trigger")} tone="amber" />}
                  <StatusBadge value={node.signal_family || node.signalFamily || node.component || "signal"} tone={severityTone(node.severity)} t={t} />
                  {Boolean(node.evidence_type) && <StatusBadge value={node.evidence_type} tone="blue" t={t} />}
                  {qualityStatus && qualityStatus !== "complete" && <StatusBadge value={qualityStatus} tone={qualityTone(qualityStatus)} t={t} />}
                </div>
              </div>
              <div className="timeline-body">
                <time>{formatDate(node.timestamp || node.observed_at || report.created_at)}</time>
                <strong>{stringValue(node.title || node.event_type || node.eventType || node.component)}</strong>
                <span>{stringValue(node.detail || node.signal_family || node.signalFamily || node.evidence_id)}</span>
              </div>
              {score !== undefined && score !== null && (
                <div className="timeline-score">
                  <span>{t("Root candidate score")}</span>
                  <strong>{formatPercentValue(score)}</strong>
                </div>
              )}
              {incoming && (
                <div className={`timeline-relation ${incoming.inferred ? "causal" : "temporal"}`}>
                  <div>
                    <Icon name={incoming.inferred ? "diagram-3" : "arrow-right"} />
                    <span>{stringValue(source?.title) || stringValue(incoming.source)}</span>
                  </div>
                  <strong>{stringValue(incoming.relationship) || "observed sequence"}</strong>
                  <small>
                    {[incoming.rule_id || incoming.ruleId || incoming.evidence_basis || incoming.evidenceBasis, incoming.direction, incoming.strength || formatPercentValue(incoming.confidence)]
                      .filter(Boolean)
                      .map(stringValue)
                      .join(" · ")}
                  </small>
                </div>
              )}
              {evidencePaths.length > 0 && (
                <div className="timeline-paths">
                  {evidencePaths.slice(0, 4).map((path) => <code key={path}>{path}</code>)}
                </div>
              )}
            </article>
          );
        })}
      </div>
      <TimelineNodeDetail node={activeNode} incoming={selectedIncoming} source={selectedSource} report={report} t={t} />
    </>
  );
}

export function TimelineNodeDetail({ node, incoming, source, report, t }: TimelineNodeDetailProps) {
  if (!node) return null;
  const evidencePaths = stringArray(node.evidence_paths || node.evidencePaths);
  const eventType = stringValue(node.event_type || node.eventType);
  const signals = derivedSignals(report).filter((signal) => {
    const record = recordValue(signal);
    const name = stringValue(record.signal || record.name);
    const matched = stringArray(record.matched_fields || record.matchedFields);
    return name === eventType || evidencePaths.some((path) => matched.includes(path));
  });
  const supporting = signals.flatMap((signal) => {
    const record = recordValue(signal);
    return stringArray(record.supporting_evidence || record.supportingEvidence);
  }).slice(0, 5);
  const quality = recordValue(node.evidence_quality || node.evidenceQuality);
  return (
    <div className="timeline-node-detail">
      <div>
        <p className="section-kicker">{t("Selected timeline evidence")}</p>
        <h3>{stringValue(node.title || eventType || node.component)}</h3>
        <div className="timeline-detail-meta">
          <StatusBadge value={node.severity || "info"} tone={severityTone(node.severity)} t={t} />
          <StatusBadge value={node.signal_family || node.signalFamily || node.component || "signal"} tone="blue" t={t} />
          {Boolean(quality.status) && <StatusBadge value={quality.status} tone={qualityTone(quality.status)} t={t} />}
        </div>
      </div>
      <div className="timeline-detail-grid">
        <div><span>{t("Observed at")}</span><strong>{formatDate(node.timestamp || node.observed_at || report.created_at)}</strong></div>
        <div><span>{t("Evidence type")}</span><strong>{stringValue(node.evidence_type || node.evidenceType) || "unknown"}</strong></div>
        <div><span>{t("Root score")}</span><strong>{formatPercentValue(node.root_cause_score ?? node.rootCauseScore)}</strong></div>
        <div><span>{t("Evidence quality")}</span><strong>{stringValue(quality.status) || "unknown"}</strong></div>
      </div>
      {incoming && (
        <div className="timeline-detail-relation">
          <span>{t("Incoming relation")}</span>
          <strong>{stringValue(source?.title) || stringValue(incoming.source)} -&gt; {stringValue(node.title || eventType)}</strong>
          <small>{[incoming.relationship, incoming.rule_id || incoming.ruleId, incoming.direction, incoming.strength].filter(Boolean).map(stringValue).join(" / ")}</small>
        </div>
      )}
      {evidencePaths.length > 0 && (
        <div className="timeline-paths">
          {evidencePaths.map((path) => <code key={path}>{path}</code>)}
        </div>
      )}
      {supporting.length > 0 && (
        <div className="supporting-lines">
          {supporting.map((line, index) => <span key={`${line}-${index}`}>{line}</span>)}
        </div>
      )}
    </div>
  );
}

function recordValue(value: unknown): TimelineRecord {
  return value !== null && typeof value === "object" && !Array.isArray(value) ? value as TimelineRecord : {};
}

function arrayRecords(value: unknown): TimelineRecord[] {
  return Array.isArray(value) ? value.map(recordValue) : [];
}

function stringArray(value: unknown): string[] {
  return Array.isArray(value) ? value.map((item) => String(item)) : [];
}

function stringValue(value: unknown): string {
  return value === null || value === undefined ? "" : String(value);
}

function nodeKey(node: TimelineRecord, index: number): string {
  return stringValue(node.id) || `${stringValue(node.title || node.event_type || node.eventType || "node")}-${index}`;
}

function displayValue(value: unknown, fallback: string | number): string | number {
  if (value === null || value === undefined || value === "") return fallback;
  if (typeof value === "number" || typeof value === "string") return value;
  return String(value);
}
