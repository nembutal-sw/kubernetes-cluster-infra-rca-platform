from __future__ import annotations

import uuid

from backend.app.models import (
    AlertmanagerAlert,
    AlertmanagerPayload,
    EvidenceRequest,
    EvidenceRequestCreateRequest,
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
        created_evidence_requests: list[EvidenceRequest] = []
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

            node_name = self._node_name_for(alert)
            if node_name is not None and self._store.get_agent(cluster.cluster_id, node_name) is not None:
                evidence_request = self._store.create_evidence_request(
                    EvidenceRequestCreateRequest(
                        cluster_id=cluster.cluster_id,
                        node_name=node_name,
                        alert_name=self._alert_name_for(alert),
                        requested_collectors=self._collectors_for(alert),
                        time_range=self._time_range_for(alert),
                        reason="Alertmanager firing alert",
                        context={
                            "labels": alert.labels,
                            "annotations": alert.annotations,
                            "generator_url": alert.generator_url,
                        },
                    )
                )
                created_evidence_requests.append(evidence_request)
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
            created_evidence_requests=created_evidence_requests,
            skipped_alerts=skipped_alerts,
        )

    def _cluster_id_for(self, payload: AlertmanagerPayload, alert: AlertmanagerAlert) -> str | None:
        return (
            alert.labels.get("cluster_id")
            or payload.common_labels.get("cluster_id")
            or payload.group_labels.get("cluster_id")
        )

    def _node_name_for(self, alert: AlertmanagerAlert) -> str | None:
        return alert.labels.get("node") or alert.labels.get("nodename") or alert.labels.get("instance")

    def _alert_name_for(self, alert: AlertmanagerAlert) -> str:
        return alert.labels.get("alertname", "UnknownAlert")

    def _collectors_for(self, alert: AlertmanagerAlert) -> list[str]:
        alert_name = self._alert_name_for(alert)
        if alert_name in {"NodeNotReady", "KubeletDown", "KubeletUnhealthy"}:
            return ["node", "systemd", "runtime", "kernel", "network"]
        if alert_name == "DiskPressure":
            return ["node", "disk", "inode", "kernel", "systemd"]
        if alert_name == "MemoryPressure":
            return ["node", "memory", "kernel", "systemd"]
        if alert_name == "PIDPressure":
            return ["node", "process", "systemd", "kernel"]
        if alert_name == "NetworkUnavailable":
            return ["node", "network", "cni", "dns", "conntrack"]
        if alert_name in {"ContainerdDown", "ContainerRuntimeUnhealthy"}:
            return ["runtime", "systemd", "kernel", "disk"]
        if alert_name in {"CoreDNSUnhealthy", "CoreDNSLatencyHigh"}:
            return ["dns", "network", "cni", "conntrack"]
        if alert_name in {"EtcdLatencyHigh", "APIServerLatencyHigh"}:
            return ["network", "dns", "systemd", "kernel"]
        return ["node", "systemd", "runtime", "disk", "memory", "network", "kernel"]

    def _time_range_for(self, alert: AlertmanagerAlert) -> dict[str, str]:
        time_range: dict[str, str] = {}
        if alert.starts_at is not None:
            time_range["from"] = alert.starts_at.isoformat()
        if alert.ends_at is not None:
            time_range["to"] = alert.ends_at.isoformat()
        return time_range

    def _skip_reason(self, alert: AlertmanagerAlert, reason: str) -> str:
        alert_name = alert.labels.get("alertname", "unknown")
        return f"{alert_name}: {reason}"
