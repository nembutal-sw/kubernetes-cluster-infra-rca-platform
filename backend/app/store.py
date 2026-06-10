from __future__ import annotations

import json
import secrets
import uuid
from collections.abc import Callable
from threading import RLock
from typing import Protocol

from sqlalchemy.orm import Session

from backend.app.db_models import ClusterRow, EvidenceBundleRow, RcaJobRow, RcaReportRow
from backend.app.models import (
    Cluster,
    ClusterCreateRequest,
    ClusterStatus,
    EvidenceBundle,
    RcaJob,
    RcaJobStatus,
    RcaReport,
    RcaSummary,
    RecommendedAction,
    RootCauseCandidate,
)


class StoreProtocol(Protocol):
    def create_cluster(self, request: ClusterCreateRequest) -> Cluster: ...
    def list_clusters(self) -> list[Cluster]: ...
    def get_cluster(self, cluster_id: str) -> Cluster | None: ...
    def save_evidence(self, evidence: EvidenceBundle) -> EvidenceBundle: ...
    def get_evidence(self, evidence_id: str) -> EvidenceBundle | None: ...
    def save_job(self, job: RcaJob) -> RcaJob: ...
    def list_jobs(self) -> list[RcaJob]: ...
    def get_job(self, job_id: str) -> RcaJob | None: ...
    def save_report(self, report: RcaReport) -> RcaReport: ...
    def list_reports(self) -> list[RcaReport]: ...
    def get_report(self, report_id: str) -> RcaReport | None: ...


class InMemoryStore:
    def __init__(self) -> None:
        self._lock = RLock()
        self._clusters: dict[str, Cluster] = {}
        self._evidence: dict[str, EvidenceBundle] = {}
        self._jobs: dict[str, RcaJob] = {}
        self._reports: dict[str, RcaReport] = {}

    def create_cluster(self, request: ClusterCreateRequest) -> Cluster:
        with self._lock:
            cluster = Cluster(
                cluster_id=f"cluster-{uuid.uuid4().hex[:8]}",
                name=request.name,
                environment=request.environment,
                description=request.description,
                bootstrap_token=secrets.token_urlsafe(24),
            )
            self._clusters[cluster.cluster_id] = cluster
            return cluster

    def list_clusters(self) -> list[Cluster]:
        with self._lock:
            return list(self._clusters.values())

    def get_cluster(self, cluster_id: str) -> Cluster | None:
        with self._lock:
            return self._clusters.get(cluster_id)

    def save_evidence(self, evidence: EvidenceBundle) -> EvidenceBundle:
        with self._lock:
            if evidence.evidence_id is None:
                evidence = evidence.model_copy(update={"evidence_id": f"evidence-{uuid.uuid4().hex[:8]}"})
            self._evidence[evidence.evidence_id] = evidence
            return evidence

    def get_evidence(self, evidence_id: str) -> EvidenceBundle | None:
        with self._lock:
            return self._evidence.get(evidence_id)

    def save_job(self, job: RcaJob) -> RcaJob:
        with self._lock:
            self._jobs[job.job_id] = job
            return job

    def list_jobs(self) -> list[RcaJob]:
        with self._lock:
            return list(self._jobs.values())

    def get_job(self, job_id: str) -> RcaJob | None:
        with self._lock:
            return self._jobs.get(job_id)

    def save_report(self, report: RcaReport) -> RcaReport:
        with self._lock:
            self._reports[report.report_id] = report
            return report

    def list_reports(self) -> list[RcaReport]:
        with self._lock:
            return list(self._reports.values())

    def get_report(self, report_id: str) -> RcaReport | None:
        with self._lock:
            return self._reports.get(report_id)


class SqlAlchemyStore:
    def __init__(self, session_factory: Callable[[], Session]) -> None:
        self._session_factory = session_factory

    def create_cluster(self, request: ClusterCreateRequest) -> Cluster:
        cluster = Cluster(
            cluster_id=f"cluster-{uuid.uuid4().hex[:8]}",
            name=request.name,
            environment=request.environment,
            description=request.description,
            bootstrap_token=secrets.token_urlsafe(24),
        )
        with self._session_factory() as session:
            session.add(_cluster_to_row(cluster))
            session.commit()
        return cluster

    def list_clusters(self) -> list[Cluster]:
        with self._session_factory() as session:
            rows = session.query(ClusterRow).order_by(ClusterRow.created_at.desc()).all()
            return [_cluster_from_row(row) for row in rows]

    def get_cluster(self, cluster_id: str) -> Cluster | None:
        with self._session_factory() as session:
            row = session.get(ClusterRow, cluster_id)
            return _cluster_from_row(row) if row else None

    def save_evidence(self, evidence: EvidenceBundle) -> EvidenceBundle:
        if evidence.evidence_id is None:
            evidence = evidence.model_copy(update={"evidence_id": f"evidence-{uuid.uuid4().hex[:8]}"})

        with self._session_factory() as session:
            session.merge(_evidence_to_row(evidence))
            session.commit()
        return evidence

    def get_evidence(self, evidence_id: str) -> EvidenceBundle | None:
        with self._session_factory() as session:
            row = session.get(EvidenceBundleRow, evidence_id)
            return _evidence_from_row(row) if row else None

    def save_job(self, job: RcaJob) -> RcaJob:
        with self._session_factory() as session:
            session.merge(_job_to_row(job))
            session.commit()
        return job

    def list_jobs(self) -> list[RcaJob]:
        with self._session_factory() as session:
            rows = session.query(RcaJobRow).order_by(RcaJobRow.created_at.desc()).all()
            return [_job_from_row(row) for row in rows]

    def get_job(self, job_id: str) -> RcaJob | None:
        with self._session_factory() as session:
            row = session.get(RcaJobRow, job_id)
            return _job_from_row(row) if row else None

    def save_report(self, report: RcaReport) -> RcaReport:
        with self._session_factory() as session:
            session.merge(_report_to_row(report))
            session.commit()
        return report

    def list_reports(self) -> list[RcaReport]:
        with self._session_factory() as session:
            rows = session.query(RcaReportRow).order_by(RcaReportRow.created_at.desc()).all()
            return [_report_from_row(row) for row in rows]

    def get_report(self, report_id: str) -> RcaReport | None:
        with self._session_factory() as session:
            row = session.get(RcaReportRow, report_id)
            return _report_from_row(row) if row else None


def _json_dump(value: object) -> str:
    return json.dumps(value, ensure_ascii=False, default=str)


def _json_load(value: str):
    return json.loads(value)


def _cluster_to_row(cluster: Cluster) -> ClusterRow:
    return ClusterRow(
        cluster_id=cluster.cluster_id,
        name=cluster.name,
        environment=cluster.environment,
        description=cluster.description,
        status=cluster.status.value,
        bootstrap_token=cluster.bootstrap_token,
        created_at=cluster.created_at,
        last_seen_at=cluster.last_seen_at,
    )


def _cluster_from_row(row: ClusterRow) -> Cluster:
    return Cluster(
        cluster_id=row.cluster_id,
        name=row.name,
        environment=row.environment,
        description=row.description,
        status=ClusterStatus(row.status),
        bootstrap_token=row.bootstrap_token,
        created_at=row.created_at,
        last_seen_at=row.last_seen_at,
    )


def _evidence_to_row(evidence: EvidenceBundle) -> EvidenceBundleRow:
    if evidence.evidence_id is None:
        raise ValueError("evidence_id is required")
    return EvidenceBundleRow(
        evidence_id=evidence.evidence_id,
        cluster_id=evidence.cluster_id,
        node_name=evidence.node_name,
        alert_name=evidence.alert_name,
        collectors_json=_json_dump(evidence.collectors),
        collected_at=evidence.collected_at,
    )


def _evidence_from_row(row: EvidenceBundleRow) -> EvidenceBundle:
    return EvidenceBundle(
        evidence_id=row.evidence_id,
        cluster_id=row.cluster_id,
        node_name=row.node_name,
        alert_name=row.alert_name,
        collectors=_json_load(row.collectors_json),
        collected_at=row.collected_at,
    )


def _job_to_row(job: RcaJob) -> RcaJobRow:
    return RcaJobRow(
        job_id=job.job_id,
        cluster_id=job.cluster_id,
        alert_name=job.alert_name,
        node_name=job.node_name,
        status=job.status.value,
        report_id=job.report_id,
        evidence_id=job.evidence_id,
        created_at=job.created_at,
    )


def _job_from_row(row: RcaJobRow) -> RcaJob:
    return RcaJob(
        job_id=row.job_id,
        cluster_id=row.cluster_id,
        alert_name=row.alert_name,
        node_name=row.node_name,
        status=RcaJobStatus(row.status),
        report_id=row.report_id,
        evidence_id=row.evidence_id,
        created_at=row.created_at,
    )


def _report_to_row(report: RcaReport) -> RcaReportRow:
    return RcaReportRow(
        report_id=report.report_id,
        cluster_id=report.cluster_id,
        status=report.status.value,
        trigger_json=_json_dump(report.trigger),
        scope_json=_json_dump(report.scope),
        summary_json=report.summary.model_dump_json(),
        evidence_json=_json_dump(report.evidence),
        root_cause_candidates_json=_json_dump([item.model_dump(mode="json") for item in report.root_cause_candidates]),
        recommended_actions_json=_json_dump([item.model_dump(mode="json") for item in report.recommended_actions]),
        policy_decisions_json=_json_dump([item.model_dump(mode="json") for item in report.policy_decisions]),
        created_at=report.created_at,
    )


def _report_from_row(row: RcaReportRow) -> RcaReport:
    return RcaReport(
        report_id=row.report_id,
        cluster_id=row.cluster_id,
        status=RcaJobStatus(row.status),
        trigger=_json_load(row.trigger_json),
        scope=_json_load(row.scope_json),
        summary=RcaSummary.model_validate_json(row.summary_json),
        evidence=_json_load(row.evidence_json),
        root_cause_candidates=[RootCauseCandidate.model_validate(item) for item in _json_load(row.root_cause_candidates_json)],
        recommended_actions=[RecommendedAction.model_validate(item) for item in _json_load(row.recommended_actions_json)],
        policy_decisions=[RecommendedAction.model_validate(item) for item in _json_load(row.policy_decisions_json)],
        created_at=row.created_at,
    )
