import { useState } from "react";

import { EmptyState, Icon, PageHeader, StatusBadge, Surface } from "../components/common";
import { CursorPager } from "../components/CursorPager";
import { useCursorPage, useDebouncedValue } from "../hooks/useCursorPage";
import type { ApiCall, ClusterView, IncidentView, TFunction, UserAccount } from "../types";

type MaybePromise<T = void> = T | Promise<T>;

interface IncidentsViewProps {
  callApi: ApiCall;
  clusters: ClusterView[];
  refreshToken?: string;
  selectedIncidentId?: string;
  onSelectIncident: (incidentId: string) => void;
  onOpenReport: (reportId: string) => void;
  onChangeStatus: (incident: IncidentView, nextStatus: "resolve" | "reopen") => MaybePromise;
  currentUser: UserAccount;
  t: TFunction;
}

export function IncidentsView({ callApi, clusters, refreshToken, selectedIncidentId, onSelectIncident, onOpenReport, onChangeStatus, currentUser, t }: IncidentsViewProps) {
  const canOperate = ["admin", "operator"].includes(currentUser.role);
  const [query, setQuery] = useState("");
  const [clusterId, setClusterId] = useState("");
  const [status, setStatus] = useState("");
  const debouncedQuery = useDebouncedValue(query);
  const result = useCursorPage<IncidentView>(callApi, "/api/v1/rca/incidents", {
    q: debouncedQuery,
    cluster_id: clusterId,
    status,
  }, refreshToken);
  const incidents = result.page.items;
  const orderedIncidents = selectedIncidentId
    ? [...incidents].sort((left, right) => Number(right.incident_id === selectedIncidentId) - Number(left.incident_id === selectedIncidentId))
    : incidents;
  return (
    <div className="page-stack">
      <PageHeader title={t("Incidents")} subtitle={t("Correlated evidence grouped by node, cause, and recurrence.")} />
      <div className="ops-filter-bar">
        <div className="input-group input-group-sm ops-search-control">
          <span className="input-group-text"><Icon name="search" /></span>
          <input className="form-control" value={query} onChange={(event) => setQuery(event.target.value)} placeholder={t("Search incidents")} aria-label={t("Search incidents")} />
        </div>
        <select className="form-select form-select-sm" value={clusterId} onChange={(event) => setClusterId(event.target.value)} aria-label={t("Filter by cluster")}>
          <option value="">{t("All clusters")}</option>
          {clusters.map((cluster) => <option key={cluster.cluster_id} value={cluster.cluster_id}>{cluster.name}</option>)}
        </select>
        <select className="form-select form-select-sm" value={status} onChange={(event) => setStatus(event.target.value)} aria-label={t("Filter by status")}>
          <option value="">{t("All statuses")}</option>
          <option value="open">{t("open")}</option>
          <option value="resolved">{t("resolved")}</option>
        </select>
      </div>
      {result.error && <div className="alert alert-warning py-2 mb-0">{result.error.detail}</div>}
      <Surface title={t("Incidents")} subtitle={`${result.page.total} ${t("total")}`}>
        <div className="incident-list">
          {orderedIncidents.length ? orderedIncidents.map((incident) => (
            <article
              key={incident.incident_id}
              className={`incident-item ${incident.incident_id === selectedIncidentId ? "selected" : ""}`}
              data-testid={incident.incident_id === selectedIncidentId ? "selected-incident" : undefined}
            >
              <div>
                <StatusBadge value={incident.status} tone={incident.status === "open" ? "red" : "green"} t={t} />
                <h3>{incident.alert_name}</h3>
                <p>{incident.root_cause || t("Root cause not available yet.")}</p>
                <div className="meta-row">
                  <span>{incident.cluster_id}</span>
                  <span>{(incident.node_names || [incident.node_name]).filter(Boolean).join(", ")}</span>
                  <span>{incident.occurrence_count || 0}x</span>
                </div>
              </div>
              <div className="incident-actions">
                {incident.incident_id !== selectedIncidentId && (
                  <button className="btn btn-sm btn-outline-secondary" onClick={() => onSelectIncident(incident.incident_id)}>
                    {t("View incident")}
                  </button>
                )}
                {incident.latest_report_id && (
                  <button className="btn btn-sm btn-outline-secondary" onClick={() => onOpenReport(incident.latest_report_id || "")}>
                    {t("RCA Reports")}
                  </button>
                )}
                {canOperate && incident.status === "open" && (
                  <button className="btn btn-sm btn-success" onClick={() => onChangeStatus(incident, "resolve")}>{t("Resolve")}</button>
                )}
                {canOperate && incident.status === "resolved" && (
                  <button className="btn btn-sm btn-outline-secondary" onClick={() => onChangeStatus(incident, "reopen")}>{t("Reopen")}</button>
                )}
              </div>
            </article>
          )) : <EmptyState message={t("No incidents loaded.")} />}
        </div>
        <CursorPager page={result.pageNumber} total={result.page.total} loading={result.loading} canPrevious={result.canPrevious} canNext={result.canNext} onPrevious={result.previous} onNext={result.next} t={t} />
      </Surface>
    </div>
  );
}
