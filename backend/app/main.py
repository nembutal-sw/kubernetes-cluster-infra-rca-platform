from __future__ import annotations

from fastapi import FastAPI, HTTPException, status

from backend.app.config import load_settings
from backend.app.database import create_db_engine, create_session_factory, create_tables
from backend.app.models import AlertmanagerPayload, Cluster, ClusterCreateRequest, RcaJob, RcaReport
from backend.app.services.analyzer import RuleBasedRcaAnalyzer
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
    def get_install_command(cluster_id: str):
        response = rca_service.build_install_command(cluster_id)
        if response is None:
            raise HTTPException(status_code=404, detail="cluster not found")
        return response

    @app.post("/api/webhooks/alertmanager")
    def ingest_alertmanager(payload: AlertmanagerPayload):
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


app = create_app()
