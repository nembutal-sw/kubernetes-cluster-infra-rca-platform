from __future__ import annotations

import json
import secrets
import uuid
from collections.abc import Callable
from threading import RLock
from typing import Protocol

from sqlalchemy.orm import Session

from backend.app.db_models import ClusterRow, EvidenceBundleRow, EvidenceRequestRow, NodeAgentRow, RcaJobRow, RcaReportRow
from backend.app.models import (
    AgentStatus,
    AgentEvidenceSubmitRequest,
    Cluster,
    ClusterCreateRequest,
    ClusterStatus,
    EvidenceBundle,
    EvidenceRequest,
    EvidenceRequestCreateRequest,
    EvidenceRequestStatus,
    NodeAgent,
    NodeAgentHeartbeatRequest,
    NodeAgentRegisterRequest,
    RcaJob,
    RcaJobStatus,
    RcaReport,
    RcaSummary,
    RecommendedAction,
    RootCauseCandidate,
    now_utc,
)


class StoreProtocol(Protocol):
    def create_cluster(self, request: ClusterCreateRequest) -> Cluster: ...
    def list_clusters(self) -> list[Cluster]: ...
    def get_cluster(self, cluster_id: str) -> Cluster | None: ...
    def register_agent(self, request: NodeAgentRegisterRequest) -> NodeAgent: ...
    def record_agent_heartbeat(self, request: NodeAgentHeartbeatRequest) -> NodeAgent | None: ...
    def list_agents(self, cluster_id: str | None = None) -> list[NodeAgent]: ...
    def get_agent(self, cluster_id: str, node_name: str) -> NodeAgent | None: ...
    def create_evidence_request(self, request: EvidenceRequestCreateRequest) -> EvidenceRequest: ...
    def list_evidence_requests(
        self,
        cluster_id: str | None = None,
        node_name: str | None = None,
        status: EvidenceRequestStatus | None = None,
        limit: int | None = None,
    ) -> list[EvidenceRequest]: ...
    def get_evidence_request(self, request_id: str) -> EvidenceRequest | None: ...
    def submit_evidence_response(self, request: AgentEvidenceSubmitRequest) -> EvidenceRequest | None: ...
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
        self._agents: dict[tuple[str, str], NodeAgent] = {}
        self._evidence_requests: dict[str, EvidenceRequest] = {}
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

    def register_agent(self, request: NodeAgentRegisterRequest) -> NodeAgent:
        with self._lock:
            existing = self._agents.get((request.cluster_id, request.node_name))
            registered_at = existing.registered_at if existing else now_utc()
            agent = NodeAgent(
                agent_id=existing.agent_id if existing else f"agent-{uuid.uuid4().hex[:8]}",
                cluster_id=request.cluster_id,
                node_name=request.node_name,
                agent_version=request.agent_version,
                status=AgentStatus.REGISTERED,
                supported_collectors=request.supported_collectors,
                metadata=request.metadata,
                health=existing.health if existing else {},
                registered_at=registered_at,
                last_heartbeat_at=existing.last_heartbeat_at if existing else None,
            )
            self._agents[(request.cluster_id, request.node_name)] = agent
            self._mark_cluster_active(request.cluster_id)
            return agent

    def record_agent_heartbeat(self, request: NodeAgentHeartbeatRequest) -> NodeAgent | None:
        with self._lock:
            existing = self._agents.get((request.cluster_id, request.node_name))
            if existing is None:
                return None
            agent = existing.model_copy(
                update={
                    "status": request.status,
                    "agent_version": request.agent_version or existing.agent_version,
                    "supported_collectors": request.supported_collectors
                    if request.supported_collectors is not None
                    else existing.supported_collectors,
                    "health": request.health,
                    "last_heartbeat_at": now_utc(),
                }
            )
            self._agents[(request.cluster_id, request.node_name)] = agent
            self._mark_cluster_active(request.cluster_id)
            return agent

    def list_agents(self, cluster_id: str | None = None) -> list[NodeAgent]:
        with self._lock:
            agents = list(self._agents.values())
            if cluster_id is not None:
                agents = [agent for agent in agents if agent.cluster_id == cluster_id]
            return agents

    def get_agent(self, cluster_id: str, node_name: str) -> NodeAgent | None:
        with self._lock:
            return self._agents.get((cluster_id, node_name))

    def create_evidence_request(self, request: EvidenceRequestCreateRequest) -> EvidenceRequest:
        with self._lock:
            evidence_request = EvidenceRequest(
                request_id=f"evidence-request-{uuid.uuid4().hex[:8]}",
                cluster_id=request.cluster_id,
                node_name=request.node_name,
                alert_name=request.alert_name,
                requested_collectors=request.requested_collectors,
                status=EvidenceRequestStatus.PENDING,
                time_range=request.time_range,
                reason=request.reason,
                context=request.context,
            )
            self._evidence_requests[evidence_request.request_id] = evidence_request
            return evidence_request

    def list_evidence_requests(
        self,
        cluster_id: str | None = None,
        node_name: str | None = None,
        status: EvidenceRequestStatus | None = None,
        limit: int | None = None,
    ) -> list[EvidenceRequest]:
        with self._lock:
            requests = list(self._evidence_requests.values())
            requests = _filter_evidence_requests(requests, cluster_id, node_name, status)
            requests.sort(key=lambda item: item.created_at)
            return requests[:limit] if limit is not None else requests

    def get_evidence_request(self, request_id: str) -> EvidenceRequest | None:
        with self._lock:
            return self._evidence_requests.get(request_id)

    def submit_evidence_response(self, request: AgentEvidenceSubmitRequest) -> EvidenceRequest | None:
        with self._lock:
            evidence_request = self._evidence_requests.get(request.request_id)
            if evidence_request is None:
                return None
            if request.status == EvidenceRequestStatus.COMPLETED:
                evidence = self.save_evidence(
                    EvidenceBundle(
                        cluster_id=request.cluster_id,
                        node_name=request.node_name,
                        alert_name=evidence_request.alert_name,
                        collectors=request.collectors,
                    )
                )
                evidence_request = evidence_request.model_copy(
                    update={
                        "status": EvidenceRequestStatus.COMPLETED,
                        "evidence_id": evidence.evidence_id,
                        "error_message": None,
                        "completed_at": now_utc(),
                    }
                )
            else:
                evidence_request = evidence_request.model_copy(
                    update={
                        "status": EvidenceRequestStatus.FAILED,
                        "error_message": request.error_message,
                        "completed_at": now_utc(),
                    }
                )
            self._evidence_requests[request.request_id] = evidence_request
            self._mark_cluster_active(request.cluster_id)
            return evidence_request

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

    def _mark_cluster_active(self, cluster_id: str) -> None:
        cluster = self._clusters.get(cluster_id)
        if cluster is None:
            return
        self._clusters[cluster_id] = cluster.model_copy(
            update={
                "status": ClusterStatus.ACTIVE,
                "last_seen_at": now_utc(),
            }
        )


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

    def register_agent(self, request: NodeAgentRegisterRequest) -> NodeAgent:
        with self._session_factory() as session:
            existing = (
                session.query(NodeAgentRow)
                .filter(NodeAgentRow.cluster_id == request.cluster_id, NodeAgentRow.node_name == request.node_name)
                .one_or_none()
            )
            agent = NodeAgent(
                agent_id=existing.agent_id if existing else f"agent-{uuid.uuid4().hex[:8]}",
                cluster_id=request.cluster_id,
                node_name=request.node_name,
                agent_version=request.agent_version,
                status=AgentStatus.REGISTERED,
                supported_collectors=request.supported_collectors,
                metadata=request.metadata,
                health=_json_load(existing.health_json) if existing else {},
                registered_at=existing.registered_at if existing else now_utc(),
                last_heartbeat_at=existing.last_heartbeat_at if existing else None,
            )
            session.merge(_agent_to_row(agent))
            _mark_cluster_active(session, request.cluster_id)
            session.commit()
            return agent

    def record_agent_heartbeat(self, request: NodeAgentHeartbeatRequest) -> NodeAgent | None:
        with self._session_factory() as session:
            existing = (
                session.query(NodeAgentRow)
                .filter(NodeAgentRow.cluster_id == request.cluster_id, NodeAgentRow.node_name == request.node_name)
                .one_or_none()
            )
            if existing is None:
                return None
            agent = _agent_from_row(existing).model_copy(
                update={
                    "status": request.status,
                    "agent_version": request.agent_version or existing.agent_version,
                    "supported_collectors": request.supported_collectors
                    if request.supported_collectors is not None
                    else _json_load(existing.supported_collectors_json),
                    "health": request.health,
                    "last_heartbeat_at": now_utc(),
                }
            )
            session.merge(_agent_to_row(agent))
            _mark_cluster_active(session, request.cluster_id)
            session.commit()
            return agent

    def list_agents(self, cluster_id: str | None = None) -> list[NodeAgent]:
        with self._session_factory() as session:
            query = session.query(NodeAgentRow)
            if cluster_id is not None:
                query = query.filter(NodeAgentRow.cluster_id == cluster_id)
            rows = query.order_by(NodeAgentRow.node_name.asc()).all()
            return [_agent_from_row(row) for row in rows]

    def get_agent(self, cluster_id: str, node_name: str) -> NodeAgent | None:
        with self._session_factory() as session:
            row = (
                session.query(NodeAgentRow)
                .filter(NodeAgentRow.cluster_id == cluster_id, NodeAgentRow.node_name == node_name)
                .one_or_none()
            )
            return _agent_from_row(row) if row else None

    def create_evidence_request(self, request: EvidenceRequestCreateRequest) -> EvidenceRequest:
        evidence_request = EvidenceRequest(
            request_id=f"evidence-request-{uuid.uuid4().hex[:8]}",
            cluster_id=request.cluster_id,
            node_name=request.node_name,
            alert_name=request.alert_name,
            requested_collectors=request.requested_collectors,
            status=EvidenceRequestStatus.PENDING,
            time_range=request.time_range,
            reason=request.reason,
            context=request.context,
        )
        with self._session_factory() as session:
            session.add(_evidence_request_to_row(evidence_request))
            session.commit()
        return evidence_request

    def list_evidence_requests(
        self,
        cluster_id: str | None = None,
        node_name: str | None = None,
        status: EvidenceRequestStatus | None = None,
        limit: int | None = None,
    ) -> list[EvidenceRequest]:
        with self._session_factory() as session:
            query = session.query(EvidenceRequestRow)
            if cluster_id is not None:
                query = query.filter(EvidenceRequestRow.cluster_id == cluster_id)
            if node_name is not None:
                query = query.filter(EvidenceRequestRow.node_name == node_name)
            if status is not None:
                query = query.filter(EvidenceRequestRow.status == status.value)
            query = query.order_by(EvidenceRequestRow.created_at.asc())
            if limit is not None:
                query = query.limit(limit)
            return [_evidence_request_from_row(row) for row in query.all()]

    def get_evidence_request(self, request_id: str) -> EvidenceRequest | None:
        with self._session_factory() as session:
            row = session.get(EvidenceRequestRow, request_id)
            return _evidence_request_from_row(row) if row else None

    def submit_evidence_response(self, request: AgentEvidenceSubmitRequest) -> EvidenceRequest | None:
        with self._session_factory() as session:
            row = session.get(EvidenceRequestRow, request.request_id)
            if row is None:
                return None
            evidence_request = _evidence_request_from_row(row)
            if request.status == EvidenceRequestStatus.COMPLETED:
                evidence = EvidenceBundle(
                    cluster_id=request.cluster_id,
                    node_name=request.node_name,
                    alert_name=evidence_request.alert_name,
                    collectors=request.collectors,
                )
                if evidence.evidence_id is None:
                    evidence = evidence.model_copy(update={"evidence_id": f"evidence-{uuid.uuid4().hex[:8]}"})
                session.merge(_evidence_to_row(evidence))
                evidence_request = evidence_request.model_copy(
                    update={
                        "status": EvidenceRequestStatus.COMPLETED,
                        "evidence_id": evidence.evidence_id,
                        "error_message": None,
                        "completed_at": now_utc(),
                    }
                )
            else:
                evidence_request = evidence_request.model_copy(
                    update={
                        "status": EvidenceRequestStatus.FAILED,
                        "error_message": request.error_message,
                        "completed_at": now_utc(),
                    }
                )
            session.merge(_evidence_request_to_row(evidence_request))
            _mark_cluster_active(session, request.cluster_id)
            session.commit()
            return evidence_request

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


def _mark_cluster_active(session: Session, cluster_id: str) -> None:
    cluster_row = session.get(ClusterRow, cluster_id)
    if cluster_row is None:
        return
    cluster_row.status = ClusterStatus.ACTIVE.value
    cluster_row.last_seen_at = now_utc()


def _filter_evidence_requests(
    requests: list[EvidenceRequest],
    cluster_id: str | None,
    node_name: str | None,
    status: EvidenceRequestStatus | None,
) -> list[EvidenceRequest]:
    if cluster_id is not None:
        requests = [request for request in requests if request.cluster_id == cluster_id]
    if node_name is not None:
        requests = [request for request in requests if request.node_name == node_name]
    if status is not None:
        requests = [request for request in requests if request.status == status]
    return requests


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


def _agent_to_row(agent: NodeAgent) -> NodeAgentRow:
    return NodeAgentRow(
        agent_id=agent.agent_id,
        cluster_id=agent.cluster_id,
        node_name=agent.node_name,
        agent_version=agent.agent_version,
        status=agent.status.value,
        supported_collectors_json=_json_dump(agent.supported_collectors),
        metadata_json=_json_dump(agent.metadata),
        health_json=_json_dump(agent.health),
        registered_at=agent.registered_at,
        last_heartbeat_at=agent.last_heartbeat_at,
    )


def _agent_from_row(row: NodeAgentRow) -> NodeAgent:
    return NodeAgent(
        agent_id=row.agent_id,
        cluster_id=row.cluster_id,
        node_name=row.node_name,
        agent_version=row.agent_version,
        status=AgentStatus(row.status),
        supported_collectors=_json_load(row.supported_collectors_json),
        metadata=_json_load(row.metadata_json),
        health=_json_load(row.health_json),
        registered_at=row.registered_at,
        last_heartbeat_at=row.last_heartbeat_at,
    )


def _evidence_request_to_row(request: EvidenceRequest) -> EvidenceRequestRow:
    return EvidenceRequestRow(
        request_id=request.request_id,
        cluster_id=request.cluster_id,
        node_name=request.node_name,
        alert_name=request.alert_name,
        requested_collectors_json=_json_dump(request.requested_collectors),
        status=request.status.value,
        time_range_json=_json_dump(request.time_range),
        reason=request.reason,
        context_json=_json_dump(request.context),
        evidence_id=request.evidence_id,
        error_message=request.error_message,
        created_at=request.created_at,
        completed_at=request.completed_at,
    )


def _evidence_request_from_row(row: EvidenceRequestRow) -> EvidenceRequest:
    return EvidenceRequest(
        request_id=row.request_id,
        cluster_id=row.cluster_id,
        node_name=row.node_name,
        alert_name=row.alert_name,
        requested_collectors=_json_load(row.requested_collectors_json),
        status=EvidenceRequestStatus(row.status),
        time_range=_json_load(row.time_range_json),
        reason=row.reason,
        context=_json_load(row.context_json),
        evidence_id=row.evidence_id,
        error_message=row.error_message,
        created_at=row.created_at,
        completed_at=row.completed_at,
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
