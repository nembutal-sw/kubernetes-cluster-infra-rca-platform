import type { TFunction } from "../types";
import { Icon } from "./common";

interface RouteStatusNoticeProps {
  resourceId: string;
  onReturn: () => void;
  t: TFunction;
}

export function RouteStatusNotice({ resourceId, onReturn, t }: RouteStatusNoticeProps) {
  return (
    <section className="route-status-notice" role="status" data-testid="route-not-found">
      <Icon name="signpost-split" />
      <div>
        <strong>{t("Requested resource was not found.")}</strong>
        <span>{t("The URL may be stale or the resource may have been removed.")}</span>
        <code>{resourceId}</code>
      </div>
      <button className="btn btn-sm btn-outline-secondary" onClick={onReturn}>
        {t("Return to list")}
      </button>
    </section>
  );
}
