// @ts-nocheck

import { EmptyState, Icon, MetricTile, PageHeader, ResponsiveTable, StatusBadge, Surface } from "../components/common";

export function WebhooksView({ endpoint, onCopy, t }) {
  const sample = `receivers:
  - name: cluster-infra-rca
    webhook_configs:
      - url: ${endpoint}
        send_resolved: true
        http_config:
          authorization:
            type: Bearer
            credentials_file: /etc/alertmanager/secrets/rca-webhook-token`;
  return (
    <div className="page-stack">
      <PageHeader title={t("Webhooks")} subtitle="Alertmanager is optional; the backend can also request evidence collection directly." />
      <div className="split-grid">
        <Surface title={t("Alertmanager endpoint")} subtitle="Protected by webhook token">
          <div className="endpoint-box">
            <code>{endpoint}</code>
            <button className="btn btn-sm btn-outline-secondary icon-button" onClick={() => onCopy(endpoint)}><Icon name="clipboard" /><span>{t("Copy")}</span></button>
          </div>
        </Surface>
        <Surface title={t("Receiver sample")} subtitle="YAML">
          <pre className="config-sample">{sample}</pre>
          <button className="btn btn-sm btn-outline-secondary icon-button" onClick={() => onCopy(sample)}><Icon name="clipboard" /><span>{t("Copy")}</span></button>
        </Surface>
      </div>
    </div>
  );
}
