from __future__ import annotations

import uuid

from backend.app.models import (
    AlertmanagerAlert,
    AlertmanagerPayload,
    InstallCommandResponse,
    RcaJob,
    RcaJobStatus,
    WebhookIngestResponse,
)
from backend.app.services.analyzer import RuleBasedRcaAnalyzer
from backend.app.services.evidence import FakeEvidenceCollector
from backend.app.store import StoreProtocol


class RcaService:
    def __init__(
        self,
        store: StoreProtocol,
        evidence_collector: FakeEvidenceCollector,
        analyzer: RuleBasedRcaAnalyzer,
    ) -> None:
        self._store = store
        self._evidence_collector = evidence_collector
        self._analyzer = analyzer

    def build_install_command(self, cluster_id: str) -> InstallCommandResponse | None:
        cluster = self._store.get_cluster(cluster_id)
        if cluster is None:
            return None

        return InstallCommandResponse(
            cluster_id=cluster.cluster_id,
            namespace="rca-system",
            commands=[
                "kubectl create namespace rca-system --dry-run=client -o yaml | kubectl apply -f -",
                (
                    "kubectl -n rca-system create secret generic cluster-infra-rca-agent "
                    f"--from-literal=cluster-id={cluster.cluster_id} "
                    f"--from-literal=agent-token={cluster.bootstrap_token} "
                    "--dry-run=client -o yaml | kubectl apply -f -"
                ),
                "kubectl apply -f manifests/agent-daemonset.yaml",
            ],
            notes=[
                "MVP 단계에서는 로컬 manifest를 사용합니다.",
                "실제 배포 단계에서는 backend endpoint와 image tag가 포함된 클러스터별 manifest URL을 제공합니다.",
            ],
        )

    def ingest_alertmanager(self, payload: AlertmanagerPayload) -> WebhookIngestResponse:
        created_jobs: list[RcaJob] = []
        created_reports: list[str] = []
        skipped_alerts: list[str] = []

        for alert in payload.alerts:
            if alert.status != "firing":
                skipped_alerts.append(self._skip_reason(alert, "alert is not firing"))
                continue

            cluster_id = self._cluster_id_for(payload, alert)
            if cluster_id is None:
                skipped_alerts.append(self._skip_reason(alert, "cluster_id label is missing"))
                continue

            cluster = self._store.get_cluster(cluster_id)
            if cluster is None:
                skipped_alerts.append(self._skip_reason(alert, f"cluster {cluster_id} is not registered"))
                continue

            evidence = self._evidence_collector.collect(cluster, alert)
            evidence = self._store.save_evidence(evidence)
            report_id = f"report-{uuid.uuid4().hex[:8]}"
            report = self._analyzer.analyze(report_id, evidence)
            self._store.save_report(report)

            job = RcaJob(
                job_id=f"job-{uuid.uuid4().hex[:8]}",
                cluster_id=cluster.cluster_id,
                alert_name=evidence.alert_name,
                node_name=evidence.node_name,
                status=RcaJobStatus.COMPLETED,
                report_id=report.report_id,
                evidence_id=evidence.evidence_id,
            )
            self._store.save_job(job)
            created_jobs.append(job)
            created_reports.append(report.report_id)

        return WebhookIngestResponse(
            received_alerts=len(payload.alerts),
            created_jobs=created_jobs,
            created_reports=created_reports,
            skipped_alerts=skipped_alerts,
        )

    def _cluster_id_for(self, payload: AlertmanagerPayload, alert: AlertmanagerAlert) -> str | None:
        return (
            alert.labels.get("cluster_id")
            or payload.common_labels.get("cluster_id")
            or payload.group_labels.get("cluster_id")
        )

    def _skip_reason(self, alert: AlertmanagerAlert, reason: str) -> str:
        alert_name = alert.labels.get("alertname", "unknown")
        return f"{alert_name}: {reason}"
