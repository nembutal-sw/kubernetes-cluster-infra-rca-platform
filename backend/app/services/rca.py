from __future__ import annotations

import uuid
from urllib.parse import urlencode

from backend.app.models import (
    AlertmanagerAlert,
    AlertmanagerPayload,
    EvidenceBundle,
    EvidenceRequest,
    EvidenceRequestCreateRequest,
    EvidenceRequestStatus,
    InstallCommandResponse,
    RcaJob,
    RcaJobStatus,
    WebhookIngestResponse,
)
from backend.app.services.agent_manifest import (
    DEFAULT_AGENT_IMAGE,
    DEFAULT_AGENT_NAMESPACE,
    AgentManifestOptions,
    build_agent_manifest,
    validate_backend_url,
    validate_image,
    validate_kubernetes_name,
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

    def build_install_command(
        self,
        cluster_id: str,
        backend_url: str | None = None,
        image: str = DEFAULT_AGENT_IMAGE,
        namespace: str = DEFAULT_AGENT_NAMESPACE,
    ) -> InstallCommandResponse | None:
        cluster = self._store.get_cluster(cluster_id)
        if cluster is None:
            return None
        image = validate_image(image)
        namespace = validate_kubernetes_name(namespace, "namespace")
        manifest_command = "kubectl apply -f manifests/agent-daemonset.yaml"
        notes = [
            "backend_url을 제공하면 클러스터별 manifest URL을 사용합니다.",
            "backend_url을 생략하면 repo의 로컬 manifest를 적용합니다.",
        ]
        if backend_url is not None:
            backend_url = validate_backend_url(backend_url)
            manifest_query = urlencode(
                {
                    "backend_url": backend_url,
                    "image": image,
                    "namespace": namespace,
                }
            )
            manifest_url = f"{backend_url}/api/clusters/{cluster.cluster_id}/agent-manifest?{manifest_query}"
            manifest_command = f'kubectl apply -f "{manifest_url}"'
            notes = [
                "Secret에는 cluster_id와 agent token이 들어갑니다. 출력된 명령어를 안전하게 취급해야 합니다.",
                "manifest URL은 backend URL, image, namespace 값을 포함해 클러스터별 DaemonSet을 생성합니다.",
            ]

        return InstallCommandResponse(
            cluster_id=cluster.cluster_id,
            namespace=namespace,
            commands=[
                f"kubectl create namespace {namespace} --dry-run=client -o yaml | kubectl apply -f -",
                (
                    f"kubectl -n {namespace} create secret generic cluster-infra-rca-agent "
                    f"--from-literal=cluster-id={cluster.cluster_id} "
                    f"--from-literal=agent-token={cluster.bootstrap_token} "
                    "--dry-run=client -o yaml | kubectl apply -f -"
                ),
                manifest_command,
            ],
            notes=notes,
        )

    def build_agent_manifest(self, cluster_id: str, options: AgentManifestOptions) -> dict[str, object] | None:
        cluster = self._store.get_cluster(cluster_id)
        if cluster is None:
            return None
        return build_agent_manifest(cluster, options)

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
            job = self._create_completed_job(evidence)
            created_jobs.append(job)
            created_reports.append(job.report_id)

        return WebhookIngestResponse(
            received_alerts=len(payload.alerts),
            created_jobs=created_jobs,
            created_reports=created_reports,
            created_evidence_requests=created_evidence_requests,
            skipped_alerts=skipped_alerts,
        )

    def create_report_from_evidence_request(self, evidence_request: EvidenceRequest) -> RcaJob | None:
        if evidence_request.status != EvidenceRequestStatus.COMPLETED:
            return None
        if evidence_request.evidence_id is None:
            return None

        evidence = self._store.get_evidence(evidence_request.evidence_id)
        if evidence is None:
            return None

        return self._create_completed_job(evidence)

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

    def _create_completed_job(self, evidence: EvidenceBundle) -> RcaJob:
        report_id = f"report-{uuid.uuid4().hex[:8]}"
        report = self._analyzer.analyze(report_id, evidence)
        job = RcaJob(
            job_id=f"job-{uuid.uuid4().hex[:8]}",
            cluster_id=evidence.cluster_id,
            alert_name=evidence.alert_name,
            node_name=evidence.node_name,
            status=RcaJobStatus.COMPLETED,
            report_id=report.report_id,
            evidence_id=evidence.evidence_id,
        )
        return self._store.save_report_and_job(report, job)

    def _skip_reason(self, alert: AlertmanagerAlert, reason: str) -> str:
        alert_name = alert.labels.get("alertname", "unknown")
        return f"{alert_name}: {reason}"
