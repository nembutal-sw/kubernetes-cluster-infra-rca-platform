import { useCallback } from "react";

import { buildAuditQuery, sortByTime } from "../lib/consoleUtils";
import type { ApiCall, AuditEventView, DownloadApi, NotifyFunction, TFunction } from "../types";

interface AuditSearchOptions {
  callApi: ApiCall;
  downloadApi: DownloadApi;
  notify: NotifyFunction;
  t: TFunction;
  setAuditEvents: (events: AuditEventView[]) => void;
}

export function useAuditSearch(options: AuditSearchOptions) {
  const { callApi, downloadApi, notify, t, setAuditEvents } = options;

  const searchAudit = useCallback(async (filters: Record<string, unknown>) => {
    const query = buildAuditQuery(filters);
    const next = await callApi<AuditEventView[]>(`/api/audit/events?${query}`);
    setAuditEvents(sortByTime(Array.isArray(next) ? next : [], "created_at"));
  }, [callApi, setAuditEvents]);

  const exportAudit = useCallback(async (format = "json", filters: Record<string, unknown> = {}) => {
    const query = buildAuditQuery({ ...filters, format, limit: 5000 });
    await downloadApi(`/api/audit/events/export?${query}`, `audit-events.${format}`);
    notify(t("Audit export downloaded."));
  }, [downloadApi, notify, t]);

  return { searchAudit, exportAudit };
}
