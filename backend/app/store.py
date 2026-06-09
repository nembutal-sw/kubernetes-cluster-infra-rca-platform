from __future__ import annotations

import secrets
import uuid
from threading import RLock

from backend.app.models import Cluster, ClusterCreateRequest, RcaJob, RcaReport


class InMemoryStore:
    def __init__(self) -> None:
        self._lock = RLock()
        self._clusters: dict[str, Cluster] = {}
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

