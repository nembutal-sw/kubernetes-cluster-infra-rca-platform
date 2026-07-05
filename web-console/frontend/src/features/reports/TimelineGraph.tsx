// @ts-nocheck

import { useState } from "react";

import { EmptyState, Icon, MetricTile, StatusBadge, Surface } from "../../components/common";

import { fallbackTimeline, formatDate, severityTone } from "../../lib/consoleUtils";

export function TimelineGraph({ timeline, report, t = (x) => x }) {
  const nodes = timeline?.nodes?.length ? timeline.nodes : fallbackTimeline(report);
  const [selectedNodeId, setSelectedNodeId] = useState(null);
  if (!nodes.length) return <EmptyState message="Timeline evidence is not available." />;
  const edges = Array.isArray(timeline?.edges) ? timeline.edges : [];
  const summary = timeline?.summary || {};
  const edgeByTarget = new Map(edges.map((edge) => [edge.target, edge]));
  const nodeById = new Map(nodes.map((node) => [node.id, node]));
  const rootNode = nodes.find((node) => node.root_trigger || node.rootTrigger) || nodeById.get(summary.root_node_id);
  const selectedNode = nodeById.get(selectedNodeId) || rootNode || nodes[0];
  const selectedIncoming = selectedNode ? edgeByTarget.get(selectedNode.id) : null;
  const selectedSource = selectedIncoming ? nodeById.get(selectedIncoming.source) : null;
  return (
    <>
      <div className="timeline-summary-bar">
        <article>
          <span>{t("Root trigger")}</span>
          <strong>{rootNode?.title || summary.root_title || "unknown"}</strong>
        </article>
        <article>
          <span>{t("Causal edges")}</span>
          <strong>{summary.causal_edge_count ?? edges.filter((edge) => edge.inferred).length}</strong>
        </article>
        <article>
          <span>{t("Observed edges")}</span>
          <strong>{summary.temporal_edge_count ?? edges.filter((edge) => !edge.inferred).length}</strong>
        </article>
        <article>
          <span>{t("Evidence quality")}</span>
          <StatusBadge value={summary.quality_status || "unknown"} tone={qualityTone(summary.quality_status)} t={t} />
        </article>
      </div>
      <div className="timeline-graph enhanced">
        {nodes.slice(0, 12).map((node, index) => {
          const root = Boolean(node.root_trigger || node.rootTrigger);
          const incoming = edgeByTarget.get(node.id);
          const source = incoming ? nodeById.get(incoming.source) : null;
          const evidencePaths = node.evidence_paths || node.evidencePaths || [];
          const quality = node.evidence_quality || node.evidenceQuality || {};
          const qualityStatus = quality.status || quality.evidence_status || quality.evidenceStatus;
          const score = node.root_cause_score ?? node.rootCauseScore;
          const selected = selectedNode?.id === node.id;
          return (
            <article
              key={node.id || `${node.title}-${index}`}
              className={`${root ? "root" : ""} ${incoming?.inferred ? "inferred" : "observed"} ${selected ? "selected" : ""}`}
              role="button"
              tabIndex={0}
              onClick={() => setSelectedNodeId(node.id)}
              onKeyDown={(event) => {
                if (event.key === "Enter" || event.key === " ") {
                  event.preventDefault();
                  setSelectedNodeId(node.id);
                }
              }}
            >
              <div className="timeline-card-head">
                <div className="timeline-dot"><Icon name={root ? "bullseye" : signalIcon(node.component || node.signal_family || node.signalFamily)} /></div>
                <div className="timeline-badges">
                  {root && <StatusBadge value={t("Root trigger")} tone="amber" />}
                  <StatusBadge value={node.signal_family || node.signalFamily || node.component || "signal"} tone={severityTone(node.severity)} t={t} />
                  {node.evidence_type && <StatusBadge value={node.evidence_type} tone="blue" t={t} />}
                  {qualityStatus && qualityStatus !== "complete" && <StatusBadge value={qualityStatus} tone={qualityTone(qualityStatus)} t={t} />}
                </div>
              </div>
              <div className="timeline-body">
                <time>{formatDate(node.timestamp || node.observed_at || report.created_at)}</time>
                <strong>{node.title || node.event_type || node.eventType || node.component}</strong>
                <span>{node.detail || node.signal_family || node.signalFamily || node.evidence_id}</span>
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
                    <span>{source?.title || incoming.source}</span>
                  </div>
                  <strong>{incoming.relationship || "observed sequence"}</strong>
                  <small>
                    {[incoming.rule_id || incoming.ruleId || incoming.evidence_basis || incoming.evidenceBasis, incoming.direction, incoming.strength || formatPercentValue(incoming.confidence)]
                      .filter(Boolean)
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
      <TimelineNodeDetail node={selectedNode} incoming={selectedIncoming} source={selectedSource} report={report} t={t} />
    </>
  );
}

export function TimelineNodeDetail({ node, incoming, source, report, t }) {
  if (!node) return null;
  const evidencePaths = node.evidence_paths || node.evidencePaths || [];
  const eventType = node.event_type || node.eventType;
  const signals = derivedSignals(report).filter((signal) => {
    const name = signal.signal || signal.name;
    const matched = signal.matched_fields || signal.matchedFields || [];
    return name === eventType || evidencePaths.some((path) => matched.includes(path));
  });
  const supporting = signals.flatMap((signal) => signal.supporting_evidence || signal.supportingEvidence || []).slice(0, 5);
  const quality = node.evidence_quality || node.evidenceQuality || {};
  return (
    <div className="timeline-node-detail">
      <div>
        <p className="section-kicker">{t("Selected timeline evidence")}</p>
        <h3>{node.title || eventType || node.component}</h3>
        <div className="timeline-detail-meta">
          <StatusBadge value={node.severity || "info"} tone={severityTone(node.severity)} t={t} />
          <StatusBadge value={node.signal_family || node.signalFamily || node.component || "signal"} tone="blue" t={t} />
          {quality.status && <StatusBadge value={quality.status} tone={qualityTone(quality.status)} t={t} />}
        </div>
      </div>
      <div className="timeline-detail-grid">
        <div><span>{t("Observed at")}</span><strong>{formatDate(node.timestamp || node.observed_at || report.created_at)}</strong></div>
        <div><span>{t("Evidence type")}</span><strong>{node.evidence_type || node.evidenceType || "unknown"}</strong></div>
        <div><span>{t("Root score")}</span><strong>{formatPercentValue(node.root_cause_score ?? node.rootCauseScore)}</strong></div>
        <div><span>{t("Evidence quality")}</span><strong>{quality.status || "unknown"}</strong></div>
      </div>
      {incoming && (
        <div className="timeline-detail-relation">
          <span>{t("Incoming relation")}</span>
          <strong>{source?.title || incoming.source} -&gt; {node.title || eventType}</strong>
          <small>{[incoming.relationship, incoming.rule_id || incoming.ruleId, incoming.direction, incoming.strength].filter(Boolean).join(" / ")}</small>
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
