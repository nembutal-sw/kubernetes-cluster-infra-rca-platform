// @ts-nocheck

import { useEffect, useState } from "react";

import { EmptyState, Icon, MetricTile, PageHeader, ResponsiveTable, StatusBadge, Surface } from "../components/common";

import { arrayResult, sortByTime, copyText, buildAuditQuery, auditStats, buildSignalDigest, scoreStage, occurrences, escapeRegExp, inferSignalFamily, evidenceSummary, derivedSignals, reportEvidenceQuality, reportQualityGate, qualityTone, qualityGateTone, formatFreshness, formatPercentValue, fallbackTimeline, shortValue, platformInfoRows, formatBytes, shortHash, formatDate, runConsoleLayoutAudit, layoutElementLabel, layoutElementText, relativeTime, statusTone, policyTone, confidenceTone, severityTone, requestTone, taskTone, summarizeAgentFleet, normalizedAgentStatus, agentReason, summarizePipeline, withinHours, auditTone, agentHealthTone, signalIcon, auditClientIp, auditSummary } from "../lib/consoleUtils";

export function AuditView({ events, onSearch, onExport, t }) {
  const [filters, setFilters] = useState({ q: "", client_ip: "", event_type: "", outcome: "", limit: 200 });
  const [selectedEventId, setSelectedEventId] = useState("");
  const selectedEvent = events.find((event) => event.audit_event_id === selectedEventId) || events[0] || null;
  const stats = auditStats(events);

  useEffect(() => {
    if (events.length && !events.some((event) => event.audit_event_id === selectedEventId)) {
      setSelectedEventId(events[0].audit_event_id);
    }
  }, [events, selectedEventId]);

  async function submit(event) {
    event.preventDefault();
    await onSearch(filters);
  }

  async function quickFilter(nextFilters) {
    const merged = { ...filters, ...nextFilters };
    setFilters(merged);
    await onSearch(merged);
  }

  return (
    <div className="page-stack">
      <PageHeader title={t("Audit")} subtitle={t("Access, approval, export, agent auth, and administrative records.")} />
      <Surface title={t("Audit search")} subtitle={t("Filter by event, IP, actor, outcome, or text")}>
        <div className="quick-filter-row" aria-label={t("Quick filters")}>
          <button type="button" className="btn btn-sm btn-outline-secondary" onClick={() => quickFilter({ q: "", event_type: "", outcome: "", client_ip: "" })}>{t("All events")}</button>
          <button type="button" className="btn btn-sm btn-outline-secondary" onClick={() => quickFilter({ q: "export", event_type: "", outcome: "" })}>{t("Export events")}</button>
          <button type="button" className="btn btn-sm btn-outline-secondary" onClick={() => quickFilter({ q: "auth", event_type: "", outcome: "failed" })}>{t("Auth failures")}</button>
          <button type="button" className="btn btn-sm btn-outline-secondary" onClick={() => quickFilter({ q: "approval", event_type: "", outcome: "" })}>{t("Approvals")}</button>
        </div>
        <form className="audit-form" onSubmit={submit}>
          <input className="form-control" placeholder="q" value={filters.q} onChange={(event) => setFilters({ ...filters, q: event.target.value })} />
          <input className="form-control" placeholder={t("Client IP")} value={filters.client_ip} onChange={(event) => setFilters({ ...filters, client_ip: event.target.value })} />
          <input className="form-control" placeholder={t("Event")} value={filters.event_type} onChange={(event) => setFilters({ ...filters, event_type: event.target.value })} />
          <input className="form-control" placeholder={t("Outcome")} value={filters.outcome} onChange={(event) => setFilters({ ...filters, outcome: event.target.value })} />
          <button className="btn btn-primary">{t("Search")}</button>
          <button type="button" className="btn btn-outline-secondary" onClick={() => onExport("json", filters)}>JSON</button>
          <button type="button" className="btn btn-outline-secondary" onClick={() => onExport("csv", filters)}>CSV</button>
        </form>
      </Surface>
      <section className="audit-stats">
        <MetricTile label={t("All events")} value={events.length} tone="blue" icon="journal-check" />
        <MetricTile label={t("Export events")} value={stats.exports} tone="teal" icon="download" />
        <MetricTile label={t("Auth failures")} value={stats.failures} tone={stats.failures ? "red" : "green"} icon="shield-x" />
        <MetricTile label={t("Approvals")} value={stats.approvals} tone="amber" icon="person-check" />
      </section>
      <div className="audit-layout">
        <Surface title={t("Audit")} subtitle={`${events.length} ${t("events")}`}>
          <ResponsiveTable
            empty={t("No audit events loaded.")}
            columns={[t("Created at"), t("Actor"), t("Event"), t("Resource"), t("Outcome"), t("Client IP"), t("Details"), ""]}
            rows={events.map((event) => [
              formatDate(event.created_at),
              `${event.actor_type}/${event.actor_id || "-"}`,
              <span className={event.event_type?.includes("export") ? "audit-export-event" : ""}>{event.event_type}</span>,
              `${event.resource_type}/${event.resource_id || "-"}`,
              <StatusBadge value={event.outcome} tone={auditTone(event.outcome)} t={t} />,
              auditClientIp(event),
              <span className="text-break">{auditSummary(event.details)}</span>,
              <button className="btn btn-sm btn-outline-secondary" onClick={() => setSelectedEventId(event.audit_event_id)}>{t("Open")}</button>,
            ])}
          />
        </Surface>
        <Surface title={t("Selected audit event")} subtitle={selectedEvent ? selectedEvent.audit_event_id : ""}>
          <AuditEventDetail event={selectedEvent} t={t} />
        </Surface>
      </div>
    </div>
  );
}

export function AuditEventDetail({ event, t }) {
  if (!event) return <EmptyState message={t("No audit event selected.")} />;
  const details = event.details || {};
  const requestKeys = ["client_ip", "client_ip_source", "remote_addr", "method", "path", "user_agent", "origin", "referer_path", "request_id"];
  return (
    <div className="audit-detail">
      <div className="audit-kv">
        <div><span>{t("Created at")}</span><strong>{formatDate(event.created_at)}</strong></div>
        <div><span>{t("Actor")}</span><strong>{event.actor_type}/{event.actor_id || "-"}</strong></div>
        <div><span>{t("Event")}</span><strong>{event.event_type}</strong></div>
        <div><span>{t("Resource")}</span><strong>{event.resource_type}/{event.resource_id || "-"}</strong></div>
        <div><span>{t("Outcome")}</span><strong><StatusBadge value={event.outcome} tone={auditTone(event.outcome)} t={t} /></strong></div>
        <div><span>{t("Client IP")}</span><strong>{auditClientIp(event)}</strong></div>
      </div>
      <div className="audit-context">
        <strong>{t("Request context")}</strong>
        <div className="audit-context-grid">
          {requestKeys.filter((key) => details[key]).map((key) => (
            <div key={key}><span>{key}</span><code>{String(details[key])}</code></div>
          ))}
        </div>
      </div>
      <pre className="audit-json">{JSON.stringify(details, null, 2)}</pre>
    </div>
  );
}
