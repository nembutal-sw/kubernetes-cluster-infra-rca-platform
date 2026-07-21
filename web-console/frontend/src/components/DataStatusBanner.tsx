import type { ConsoleDataSource, ConsoleLoadStates, TFunction } from "../types";
import { Icon } from "./common";

interface DataStatusBannerProps {
  states: ConsoleLoadStates;
  lastCompleteRefreshAt?: string;
  onRetry: () => void | Promise<void>;
  t: TFunction;
}

const SOURCE_LABELS: Record<ConsoleDataSource, string> = {
  overviewSummary: "Overview summary",
  clusters: "Clusters",
  actionRequests: "Action requests",
  agentHealth: "Agent health",
  demoScenarios: "Demo scenarios",
  platformInfo: "Platform info",
  catalogDetail: "Operational catalog",
  catalogOverrideDrafts: "Catalog override drafts",
  auditEvents: "Audit events",
  notificationHistory: "Notification history",
  llmDiagnostics: "LLM diagnostics",
  llmSetupGuide: "LLM setup guide",
};

export function DataStatusBanner({ states, lastCompleteRefreshAt, onRetry, t }: DataStatusBannerProps) {
  const failures = (Object.entries(states) as [ConsoleDataSource, ConsoleLoadStates[ConsoleDataSource]][])
    .filter(([, state]) => Boolean(state.error));
  if (!failures.length) return null;

  return (
    <section className="data-status-banner" data-testid="data-status-banner" role="status" aria-live="polite">
      <div className="data-status-summary">
        <Icon name="exclamation-triangle" />
        <div>
          <strong>{t("Some operational data could not be loaded.")}</strong>
          <span>
            {lastCompleteRefreshAt
              ? `${t("Last complete refresh")}: ${formatTime(lastCompleteRefreshAt)}`
              : t("A complete refresh has not succeeded yet.")}
          </span>
        </div>
        <button className="btn btn-sm btn-outline-danger icon-button" data-testid="data-status-retry" onClick={onRetry}>
          <Icon name="arrow-clockwise" />
          <span>{t("Retry failed data")}</span>
        </button>
      </div>
      <div className="data-status-failures">
        {failures.map(([source, state]) => {
          const error = state.error!;
          return (
            <article key={source} data-testid={`data-status-failure-${source}`}>
              <div>
                <strong>{t(SOURCE_LABELS[source])}</strong>
                {state.stale && <span className="stale-label">{t("Showing last successful data")}</span>}
              </div>
              <p>{error.detail}</p>
              <small>
                {error.status > 0 ? `HTTP ${error.status} / ` : ""}{error.code}
                {error.trace_id ? ` / trace ${error.trace_id}` : ""}
              </small>
            </article>
          );
        })}
      </div>
    </section>
  );
}

function formatTime(value: string): string {
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? value : parsed.toLocaleTimeString();
}
