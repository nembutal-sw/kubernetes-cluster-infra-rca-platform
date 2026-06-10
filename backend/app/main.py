from __future__ import annotations

from fastapi import FastAPI, HTTPException, Query, status

from backend.app.config import load_settings
from backend.app.database import create_db_engine, create_session_factory, create_tables
from backend.app.models import (
    AgentEvidencePollRequest,
    AgentEvidenceSubmitRequest,
    AlertmanagerPayload,
    Cluster,
    ClusterCreateRequest,
    EvidenceBundle,
    EvidenceRequest,
    EvidenceRequestCreateRequest,
    EvidenceRequestStatus,
    NodeAgent,
    NodeAgentHeartbeatRequest,
    NodeAgentRegisterRequest,
    RcaJob,
    RcaReport,
    WebhookIngestResponse,
)
from backend.app.services.analyzer import RuleBasedRcaAnalyzer
from backend.app.services.agent_manifest import (
    DEFAULT_AGENT_IMAGE,
    DEFAULT_AGENT_NAMESPACE,
    DEFAULT_COMMAND_TIMEOUT_SECONDS,
    DEFAULT_HTTP_TIMEOUT_SECONDS,
    DEFAULT_POLL_INTERVAL_SECONDS,
    AgentManifestOptions,
)
from backend.app.services.evidence import FakeEvidenceCollector
from backend.app.services.policy import PolicyEngine
from backend.app.services.rca import RcaService
from backend.app.store import SqlAlchemyStore, StoreProtocol


def create_app(
    database_url: str | None = None,
    store: StoreProtocol | None = None,
    auto_create_tables: bool | None = None,
) -> FastAPI:
    settings = load_settings(database_url, auto_create_tables)
    engine = None
    if store is None:
        engine = create_db_engine(settings.database_url)
        if settings.auto_create_tables:
            create_tables(engine)
        store = SqlAlchemyStore(create_session_factory(engine))

    policy_engine = PolicyEngine()
    analyzer = RuleBasedRcaAnalyzer(policy_engine)
    evidence_collector = FakeEvidenceCollector()
    rca_service = RcaService(store, evidence_collector, analyzer)

    app = FastAPI(
        title="Kubernetes Cluster Infra RCA Platform",
        version="0.1.0",
        description="Backend MVP for node and Linux-level Kubernetes cluster RCA.",
    )

    app.state.store = store
    app.state.rca_service = rca_service
    app.state.database_url = settings.database_url
    app.state.engine = engine

    @app.get("/health")
    def health() -> dict[str, str]:
        return {"status": "ok"}

    @app.post("/api/clusters", response_model=Cluster, status_code=status.HTTP_201_CREATED)
    def create_cluster(request: ClusterCreateRequest) -> Cluster:
        return store.create_cluster(request)

    @app.get("/api/clusters", response_model=list[Cluster])
    def list_clusters() -> list[Cluster]:
        return store.list_clusters()

    @app.get("/api/clusters/{cluster_id}", response_model=Cluster)
    def get_cluster(cluster_id: str) -> Cluster:
        cluster = store.get_cluster(cluster_id)
        if cluster is None:
            raise HTTPException(status_code=404, detail="cluster not found")
        return cluster

    @app.get("/api/clusters/{cluster_id}/install-command")
    def get_install_command(
        cluster_id: str,
        backend_url: str | None = Query(default=None),
        image: str = Query(default=DEFAULT_AGENT_IMAGE),
        namespace: str = Query(default=DEFAULT_AGENT_NAMESPACE),
    ):
        try:
            response = rca_service.build_install_command(
                cluster_id,
                backend_url=backend_url,
                image=image,
                namespace=namespace,
            )
        except ValueError as exc:
            raise HTTPException(status_code=422, detail=str(exc)) from exc
        if response is None:
            raise HTTPException(status_code=404, detail="cluster not found")
        return response

    @app.get("/api/clusters/{cluster_id}/agent-manifest")
    def get_agent_manifest(
        cluster_id: str,
        backend_url: str = Query(...),
        image: str = Query(default=DEFAULT_AGENT_IMAGE),
        namespace: str = Query(default=DEFAULT_AGENT_NAMESPACE),
        poll_interval_seconds: int = Query(default=DEFAULT_POLL_INTERVAL_SECONDS),
        http_timeout_seconds: int = Query(default=DEFAULT_HTTP_TIMEOUT_SECONDS),
        command_timeout_seconds: int = Query(default=DEFAULT_COMMAND_TIMEOUT_SECONDS),
    ) -> dict[str, object]:
        try:
            manifest = rca_service.build_agent_manifest(
                cluster_id,
                AgentManifestOptions(
                    backend_url=backend_url,
                    image=image,
                    namespace=namespace,
                    poll_interval_seconds=poll_interval_seconds,
                    http_timeout_seconds=http_timeout_seconds,
                    command_timeout_seconds=command_timeout_seconds,
                ),
            )
        except ValueError as exc:
            raise HTTPException(status_code=422, detail=str(exc)) from exc
        if manifest is None:
            raise HTTPException(status_code=404, detail="cluster not found")
        return manifest

    @app.get("/api/clusters/{cluster_id}/agents", response_model=list[NodeAgent])
    def list_cluster_agents(cluster_id: str) -> list[NodeAgent]:
        if store.get_cluster(cluster_id) is None:
            raise HTTPException(status_code=404, detail="cluster not found")
        return store.list_agents(cluster_id)

    @app.get("/api/clusters/{cluster_id}/agents/{node_name}", response_model=NodeAgent)
    def get_cluster_agent(cluster_id: str, node_name: str) -> NodeAgent:
        if store.get_cluster(cluster_id) is None:
            raise HTTPException(status_code=404, detail="cluster not found")
        agent = store.get_agent(cluster_id, node_name)
        if agent is None:
            raise HTTPException(status_code=404, detail="agent not found")
        return agent

    @app.post("/api/agents/register", response_model=NodeAgent, status_code=status.HTTP_201_CREATED)
    def register_agent(request: NodeAgentRegisterRequest) -> NodeAgent:
        _verify_agent_token(store, request.cluster_id, request.agent_token)
        return store.register_agent(request)

    @app.post("/api/agents/heartbeat", response_model=NodeAgent)
    def record_agent_heartbeat(request: NodeAgentHeartbeatRequest) -> NodeAgent:
        _verify_agent_token(store, request.cluster_id, request.agent_token)
        agent = store.record_agent_heartbeat(request)
        if agent is None:
            raise HTTPException(status_code=404, detail="agent not registered")
        return agent

    @app.post("/api/evidence/requests", response_model=EvidenceRequest, status_code=status.HTTP_201_CREATED)
    def create_evidence_request(request: EvidenceRequestCreateRequest) -> EvidenceRequest:
        if store.get_cluster(request.cluster_id) is None:
            raise HTTPException(status_code=404, detail="cluster not found")
        if store.get_agent(request.cluster_id, request.node_name) is None:
            raise HTTPException(status_code=404, detail="agent not found")
        return store.create_evidence_request(request)

    @app.get("/api/clusters/{cluster_id}/evidence-requests", response_model=list[EvidenceRequest])
    def list_cluster_evidence_requests(cluster_id: str) -> list[EvidenceRequest]:
        if store.get_cluster(cluster_id) is None:
            raise HTTPException(status_code=404, detail="cluster not found")
        return store.list_evidence_requests(cluster_id=cluster_id)

    @app.get("/api/evidence/requests/{request_id}", response_model=EvidenceRequest)
    def get_evidence_request(request_id: str) -> EvidenceRequest:
        evidence_request = store.get_evidence_request(request_id)
        if evidence_request is None:
            raise HTTPException(status_code=404, detail="evidence request not found")
        return evidence_request

    @app.get("/api/evidence/{evidence_id}", response_model=EvidenceBundle)
    def get_evidence(evidence_id: str) -> EvidenceBundle:
        evidence = store.get_evidence(evidence_id)
        if evidence is None:
            raise HTTPException(status_code=404, detail="evidence not found")
        return evidence

    @app.post("/api/agents/evidence-requests", response_model=list[EvidenceRequest])
    def poll_agent_evidence_requests(request: AgentEvidencePollRequest) -> list[EvidenceRequest]:
        _verify_agent_token(store, request.cluster_id, request.agent_token)
        if store.get_agent(request.cluster_id, request.node_name) is None:
            raise HTTPException(status_code=404, detail="agent not registered")
        return store.list_evidence_requests(
            cluster_id=request.cluster_id,
            node_name=request.node_name,
            status=EvidenceRequestStatus.PENDING,
            limit=request.limit,
        )

    @app.post("/api/agents/evidence-responses", response_model=EvidenceRequest)
    def submit_agent_evidence_response(request: AgentEvidenceSubmitRequest) -> EvidenceRequest:
        _verify_agent_token(store, request.cluster_id, request.agent_token)
        if store.get_agent(request.cluster_id, request.node_name) is None:
            raise HTTPException(status_code=404, detail="agent not registered")
        if request.status not in {EvidenceRequestStatus.COMPLETED, EvidenceRequestStatus.FAILED}:
            raise HTTPException(status_code=422, detail="evidence response status must be completed or failed")
        evidence_request = store.get_evidence_request(request.request_id)
        if evidence_request is None:
            raise HTTPException(status_code=404, detail="evidence request not found")
        if evidence_request.cluster_id != request.cluster_id or evidence_request.node_name != request.node_name:
            raise HTTPException(status_code=403, detail="evidence request is assigned to another agent")
        if evidence_request.status != EvidenceRequestStatus.PENDING:
            raise HTTPException(status_code=409, detail="evidence request is already closed")
        submitted = store.submit_evidence_response(request)
        if submitted is None:
            raise HTTPException(status_code=404, detail="evidence request not found")
        if submitted.status == EvidenceRequestStatus.COMPLETED:
            job = rca_service.create_report_from_evidence_request(submitted)
            if job is None:
                raise HTTPException(status_code=500, detail="completed evidence could not create RCA report")
        return submitted

    @app.post("/api/webhooks/alertmanager", response_model=WebhookIngestResponse)
    def ingest_alertmanager(payload: AlertmanagerPayload) -> WebhookIngestResponse:
        return rca_service.ingest_alertmanager(payload)

    @app.get("/api/rca/jobs", response_model=list[RcaJob])
    def list_rca_jobs() -> list[RcaJob]:
        return store.list_jobs()

    @app.get("/api/rca/jobs/{job_id}", response_model=RcaJob)
    def get_rca_job(job_id: str) -> RcaJob:
        job = store.get_job(job_id)
        if job is None:
            raise HTTPException(status_code=404, detail="RCA job not found")
        return job

    @app.get("/api/rca/reports", response_model=list[RcaReport])
    def list_rca_reports() -> list[RcaReport]:
        return store.list_reports()

    @app.get("/api/rca/reports/{report_id}", response_model=RcaReport)
    def get_rca_report(report_id: str) -> RcaReport:
        report = store.get_report(report_id)
        if report is None:
            raise HTTPException(status_code=404, detail="RCA report not found")
        return report

    return app


def _verify_agent_token(store: StoreProtocol, cluster_id: str, agent_token: str) -> None:
    cluster = store.get_cluster(cluster_id)
    if cluster is None:
        raise HTTPException(status_code=404, detail="cluster not found")
    if cluster.bootstrap_token != agent_token:
        raise HTTPException(status_code=401, detail="invalid agent token")


app = create_app()
