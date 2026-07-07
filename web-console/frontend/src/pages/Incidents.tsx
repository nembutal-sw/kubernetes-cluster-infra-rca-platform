import { EmptyState, PageHeader, StatusBadge, Surface } from "../components/common";
import type { IncidentView, TFunction, UserAccount } from "../types";

type MaybePromise<T = void> = T | Promise<T>;

interface IncidentsViewProps {
  incidents: IncidentView[];
  onOpenReport: (reportId: string) => void;
  onChangeStatus: (incident: IncidentView, nextStatus: "resolve" | "reopen") => MaybePromise;
  currentUser: UserAccount;
  t: TFunction;
}

export function IncidentsView({ incidents, onOpenReport, onChangeStatus, currentUser, t }: IncidentsViewProps) {
  const canOperate = ["admin", "operator"].includes(currentUser.role);
  return (
    <div className="page-stack">
      <PageHeader title={t("Incidents")} subtitle={t("Correlated evidence grouped by node, cause, and recurrence.")} />
      <Surface title={t("Incidents")} subtitle={`${incidents.length} ${t("total")}`}>
        <div className="incident-list">
          {incidents.length ? incidents.map((incident) => (
            <article key={incident.incident_id} className="incident-item">
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
      </Surface>
    </div>
  );
}
