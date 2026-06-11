from __future__ import annotations

import secrets
from datetime import timedelta
from pathlib import Path

from fastapi import FastAPI, Header, HTTPException, Query, Request, status
from fastapi.responses import FileResponse, JSONResponse
from fastapi.staticfiles import StaticFiles
from sqlalchemy import text
from sqlalchemy.exc import SQLAlchemyError

from backend.app.config import load_settings
from backend.app.database import create_db_engine, create_session_factory, create_tables
from backend.app.models import (
    AgentEvidencePollRequest,
    AgentEvidenceSubmitRequest,
    AuthSessionResponse,
    AlertmanagerPayload,
    Cluster,
    ClusterCreateRequest,
    ClusterView,
    EvidenceBundle,
    EvidenceRequest,
    EvidenceRequestCreateRequest,
    EvidenceRequestStatus,
    NodeAgent,
    NodeAgentHeartbeatRequest,
    NodeAgentRegistrationResponse,
    NodeAgentRegisterRequest,
    RcaJob,
    RcaReport,
    UserAccount,
    UserApprovalRequest,
    UserLoginRequest,
    UserRole,
    UserSignupRequest,
    UserStatus,
    WebhookIngestResponse,
    now_utc,
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
from backend.app.services.llm import LlmAnalyzer, build_llm_analyzer
from backend.app.services.policy import PolicyEngine
from backend.app.services.rca import RcaService
from backend.app.store import SqlAlchemyStore, StoreProtocol


def create_app(
    database_url: str | None = None,
    store: StoreProtocol | None = None,
    auto_create_tables: bool | None = None,
    llm_analyzer: LlmAnalyzer | None = None,
) -> FastAPI:
    settings = load_settings(database_url, auto_create_tables)
    engine = None
    if store is None:
        engine = create_db_engine(settings.database_url)
        if settings.auto_create_tables:
            create_tables(engine)
        store = SqlAlchemyStore(create_session_factory(engine))

    policy_engine = PolicyEngine()
    llm_analyzer = llm_analyzer if llm_analyzer is not None else build_llm_analyzer(settings.llm)
    analyzer = RuleBasedRcaAnalyzer(policy_engine, llm_analyzer=llm_analyzer)
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
    app.state.llm_provider = settings.llm.provider

    @app.exception_handler(SQLAlchemyError)
    async def database_exception_handler(request: Request, exc: SQLAlchemyError) -> JSONResponse:
        del request, exc
        return JSONResponse(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            content={
                "detail": "database operation failed",
                "error_code": "database_error",
            },
        )

    @app.middleware("http")
    async def add_security_headers(request: Request, call_next):
        response = await call_next(request)
        response.headers["X-Content-Type-Options"] = "nosniff"
        response.headers["X-Frame-Options"] = "DENY"
        response.headers["Referrer-Policy"] = "no-referrer"
        response.headers["Permissions-Policy"] = "camera=(), microphone=(), geolocation=()"
        if request.url.path == "/" or request.url.path.startswith("/static/"):
            response.headers["Cache-Control"] = "no-store"
            response.headers["Content-Security-Policy"] = (
                "default-src 'self'; "
                "script-src 'self'; "
                "style-src 'self'; "
                "img-src 'self' data:; "
                "connect-src 'self'; "
                "object-src 'none'; "
                "base-uri 'self'; "
                "form-action 'self'; "
                "frame-ancestors 'none'"
            )
        return response

    @app.get("/health")
    def health() -> dict[str, str]:
        return {"status": "ok"}

    @app.get("/health/ready")
    def readiness() -> dict[str, str]:
        if engine is None:
            return {"status": "ok", "database": "external_store", "llm_provider": settings.llm.provider}
        try:
            with engine.connect() as connection:
                connection.execute(text("SELECT 1"))
        except SQLAlchemyError as exc:
            raise HTTPException(
                status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                detail="database unavailable",
            ) from exc
        return {"status": "ok", "database": "reachable", "llm_provider": settings.llm.provider}

    @app.post("/api/clusters", response_model=Cluster, status_code=status.HTTP_201_CREATED)
    def create_cluster(
        request: ClusterCreateRequest,
        authorization: str | None = Header(default=None, alias="Authorization"),
        x_admin_token: str | None = Header(default=None, alias="X-Admin-Token"),
    ) -> Cluster:
        _authorize_access(
            store,
            settings.admin_approval_token,
            authorization,
            x_admin_token,
            {UserRole.ADMIN, UserRole.OPERATOR},
        )
        return store.create_cluster(request)

    @app.post("/api/auth/signup", response_model=UserAccount, status_code=status.HTTP_201_CREATED)
    def request_signup(request: UserSignupRequest) -> UserAccount:
        try:
            return store.create_user_registration(request)
        except ValueError as exc:
            raise HTTPException(status_code=409, detail=str(exc)) from exc

    @app.post("/api/auth/login", response_model=AuthSessionResponse)
    def login(request: UserLoginRequest) -> AuthSessionResponse:
        user = store.authenticate_user(request.email, request.password)
        if user is None:
            raise HTTPException(status_code=401, detail="invalid email or password")
        if user.status != UserStatus.ACTIVE:
            raise HTTPException(status_code=403, detail="user is not active")
        if user.role is None:
            raise HTTPException(status_code=403, detail="user role is not assigned")
        expires_at = now_utc() + timedelta(hours=max(settings.session_ttl_hours, 1))
        session = store.create_user_session(user.user_id, expires_at)
        if session is None:
            raise HTTPException(status_code=500, detail="failed to create session")
        access_token, _session_id = session
        return AuthSessionResponse(access_token=access_token, expires_at=expires_at, user=user)

    @app.get("/api/auth/me", response_model=UserAccount)
    def get_current_user(authorization: str | None = Header(default=None, alias="Authorization")) -> UserAccount:
        return _require_user(store, authorization, {UserRole.ADMIN, UserRole.OPERATOR, UserRole.VIEWER})

    @app.post("/api/auth/logout")
    def logout(authorization: str | None = Header(default=None, alias="Authorization")) -> dict[str, bool]:
        token = _extract_bearer_token(authorization)
        return {"revoked": store.revoke_user_session(token)}

    @app.get("/api/admin/users", response_model=list[UserAccount])
    def list_users(
        authorization: str | None = Header(default=None, alias="Authorization"),
        x_admin_token: str | None = Header(default=None, alias="X-Admin-Token"),
        user_status: UserStatus | None = Query(default=None, alias="status"),
    ) -> list[UserAccount]:
        _authorize_access(store, settings.admin_approval_token, authorization, x_admin_token, {UserRole.ADMIN})
        return store.list_users(status=user_status)

    @app.post("/api/admin/users/{user_id}/approval", response_model=UserAccount)
    def decide_user_registration(
        user_id: str,
        request: UserApprovalRequest,
        authorization: str | None = Header(default=None, alias="Authorization"),
        x_admin_token: str | None = Header(default=None, alias="X-Admin-Token"),
    ) -> UserAccount:
        _authorize_access(store, settings.admin_approval_token, authorization, x_admin_token, {UserRole.ADMIN})
        try:
            user = store.decide_user_registration(user_id, request, approved_by="platform-admin")
        except ValueError as exc:
            raise HTTPException(status_code=409, detail=str(exc)) from exc
        if user is None:
            raise HTTPException(status_code=404, detail="user not found")
        return user

    @app.get("/api/clusters", response_model=list[ClusterView])
    def list_clusters(
        authorization: str | None = Header(default=None, alias="Authorization"),
        x_admin_token: str | None = Header(default=None, alias="X-Admin-Token"),
    ) -> list[Cluster]:
        _authorize_access(
            store,
            settings.admin_approval_token,
            authorization,
            x_admin_token,
            {UserRole.ADMIN, UserRole.OPERATOR, UserRole.VIEWER},
        )
        return store.list_clusters()

    @app.get("/api/clusters/{cluster_id}", response_model=ClusterView)
    def get_cluster(
        cluster_id: str,
        authorization: str | None = Header(default=None, alias="Authorization"),
        x_admin_token: str | None = Header(default=None, alias="X-Admin-Token"),
    ) -> Cluster:
        _authorize_access(
            store,
            settings.admin_approval_token,
            authorization,
            x_admin_token,
            {UserRole.ADMIN, UserRole.OPERATOR, UserRole.VIEWER},
        )
        cluster = store.get_cluster(cluster_id)
        if cluster is None:
            raise HTTPException(status_code=404, detail="cluster not found")
        return cluster

    @app.get("/api/clusters/{cluster_id}/install-command")
    def get_install_command(
        cluster_id: str,
        authorization: str | None = Header(default=None, alias="Authorization"),
        x_admin_token: str | None = Header(default=None, alias="X-Admin-Token"),
        backend_url: str | None = Query(default=None),
        image: str = Query(default=DEFAULT_AGENT_IMAGE),
        namespace: str = Query(default=DEFAULT_AGENT_NAMESPACE),
    ):
        _authorize_access(
            store,
            settings.admin_approval_token,
            authorization,
            x_admin_token,
            {UserRole.ADMIN, UserRole.OPERATOR},
        )
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
    def list_cluster_agents(
        cluster_id: str,
        authorization: str | None = Header(default=None, alias="Authorization"),
        x_admin_token: str | None = Header(default=None, alias="X-Admin-Token"),
    ) -> list[NodeAgent]:
        _authorize_access(
            store,
            settings.admin_approval_token,
            authorization,
            x_admin_token,
            {UserRole.ADMIN, UserRole.OPERATOR, UserRole.VIEWER},
        )
        if store.get_cluster(cluster_id) is None:
            raise HTTPException(status_code=404, detail="cluster not found")
        return store.list_agents(cluster_id)

    @app.get("/api/clusters/{cluster_id}/agents/{node_name}", response_model=NodeAgent)
    def get_cluster_agent(
        cluster_id: str,
        node_name: str,
        authorization: str | None = Header(default=None, alias="Authorization"),
        x_admin_token: str | None = Header(default=None, alias="X-Admin-Token"),
    ) -> NodeAgent:
        _authorize_access(
            store,
            settings.admin_approval_token,
            authorization,
            x_admin_token,
            {UserRole.ADMIN, UserRole.OPERATOR, UserRole.VIEWER},
        )
        if store.get_cluster(cluster_id) is None:
            raise HTTPException(status_code=404, detail="cluster not found")
        agent = store.get_agent(cluster_id, node_name)
        if agent is None:
            raise HTTPException(status_code=404, detail="agent not found")
        return agent

    @app.post("/api/agents/register", response_model=NodeAgentRegistrationResponse, status_code=status.HTTP_201_CREATED)
    def register_agent(request: NodeAgentRegisterRequest) -> NodeAgentRegistrationResponse:
        _verify_agent_token(store, request.cluster_id, request.agent_token)
        return store.register_agent(request)

    @app.post("/api/agents/heartbeat", response_model=NodeAgent)
    def record_agent_heartbeat(request: NodeAgentHeartbeatRequest) -> NodeAgent:
        _verify_agent_identity(store, request.cluster_id, request.node_name, request.agent_token, request.node_token)
        agent = store.record_agent_heartbeat(request)
        if agent is None:
            raise HTTPException(status_code=404, detail="agent not registered")
        return agent

    @app.post("/api/evidence/requests", response_model=EvidenceRequest, status_code=status.HTTP_201_CREATED)
    def create_evidence_request(
        request: EvidenceRequestCreateRequest,
        authorization: str | None = Header(default=None, alias="Authorization"),
        x_admin_token: str | None = Header(default=None, alias="X-Admin-Token"),
    ) -> EvidenceRequest:
        _authorize_access(
            store,
            settings.admin_approval_token,
            authorization,
            x_admin_token,
            {UserRole.ADMIN, UserRole.OPERATOR},
        )
        if store.get_cluster(request.cluster_id) is None:
            raise HTTPException(status_code=404, detail="cluster not found")
        if store.get_agent(request.cluster_id, request.node_name) is None:
            raise HTTPException(status_code=404, detail="agent not found")
        return store.create_evidence_request(request)

    @app.get("/api/clusters/{cluster_id}/evidence-requests", response_model=list[EvidenceRequest])
    def list_cluster_evidence_requests(
        cluster_id: str,
        authorization: str | None = Header(default=None, alias="Authorization"),
        x_admin_token: str | None = Header(default=None, alias="X-Admin-Token"),
    ) -> list[EvidenceRequest]:
        _authorize_access(
            store,
            settings.admin_approval_token,
            authorization,
            x_admin_token,
            {UserRole.ADMIN, UserRole.OPERATOR, UserRole.VIEWER},
        )
        if store.get_cluster(cluster_id) is None:
            raise HTTPException(status_code=404, detail="cluster not found")
        return store.list_evidence_requests(cluster_id=cluster_id)

    @app.get("/api/evidence/requests/{request_id}", response_model=EvidenceRequest)
    def get_evidence_request(
        request_id: str,
        authorization: str | None = Header(default=None, alias="Authorization"),
        x_admin_token: str | None = Header(default=None, alias="X-Admin-Token"),
    ) -> EvidenceRequest:
        _authorize_access(
            store,
            settings.admin_approval_token,
            authorization,
            x_admin_token,
            {UserRole.ADMIN, UserRole.OPERATOR, UserRole.VIEWER},
        )
        evidence_request = store.get_evidence_request(request_id)
        if evidence_request is None:
            raise HTTPException(status_code=404, detail="evidence request not found")
        return evidence_request

    @app.get("/api/evidence/{evidence_id}", response_model=EvidenceBundle)
    def get_evidence(
        evidence_id: str,
        authorization: str | None = Header(default=None, alias="Authorization"),
        x_admin_token: str | None = Header(default=None, alias="X-Admin-Token"),
    ) -> EvidenceBundle:
        _authorize_access(
            store,
            settings.admin_approval_token,
            authorization,
            x_admin_token,
            {UserRole.ADMIN, UserRole.OPERATOR, UserRole.VIEWER},
        )
        evidence = store.get_evidence(evidence_id)
        if evidence is None:
            raise HTTPException(status_code=404, detail="evidence not found")
        return evidence

    @app.post("/api/agents/evidence-requests", response_model=list[EvidenceRequest])
    def poll_agent_evidence_requests(request: AgentEvidencePollRequest) -> list[EvidenceRequest]:
        _verify_agent_identity(store, request.cluster_id, request.node_name, request.agent_token, request.node_token)
        return store.list_evidence_requests(
            cluster_id=request.cluster_id,
            node_name=request.node_name,
            status=EvidenceRequestStatus.PENDING,
            limit=request.limit,
        )

    @app.post("/api/agents/evidence-responses", response_model=EvidenceRequest)
    def submit_agent_evidence_response(request: AgentEvidenceSubmitRequest) -> EvidenceRequest:
        _verify_agent_identity(store, request.cluster_id, request.node_name, request.agent_token, request.node_token)
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
    def ingest_alertmanager(
        payload: AlertmanagerPayload,
        authorization: str | None = Header(default=None, alias="Authorization"),
        x_webhook_token: str | None = Header(default=None, alias="X-Webhook-Token"),
    ) -> WebhookIngestResponse:
        _verify_webhook_token(settings.webhook_token, authorization, x_webhook_token)
        return rca_service.ingest_alertmanager(payload)

    @app.get("/api/rca/jobs", response_model=list[RcaJob])
    def list_rca_jobs(
        authorization: str | None = Header(default=None, alias="Authorization"),
        x_admin_token: str | None = Header(default=None, alias="X-Admin-Token"),
    ) -> list[RcaJob]:
        _authorize_access(
            store,
            settings.admin_approval_token,
            authorization,
            x_admin_token,
            {UserRole.ADMIN, UserRole.OPERATOR, UserRole.VIEWER},
        )
        return store.list_jobs()

    @app.get("/api/rca/jobs/{job_id}", response_model=RcaJob)
    def get_rca_job(
        job_id: str,
        authorization: str | None = Header(default=None, alias="Authorization"),
        x_admin_token: str | None = Header(default=None, alias="X-Admin-Token"),
    ) -> RcaJob:
        _authorize_access(
            store,
            settings.admin_approval_token,
            authorization,
            x_admin_token,
            {UserRole.ADMIN, UserRole.OPERATOR, UserRole.VIEWER},
        )
        job = store.get_job(job_id)
        if job is None:
            raise HTTPException(status_code=404, detail="RCA job not found")
        return job

    @app.get("/api/rca/reports", response_model=list[RcaReport])
    def list_rca_reports(
        authorization: str | None = Header(default=None, alias="Authorization"),
        x_admin_token: str | None = Header(default=None, alias="X-Admin-Token"),
    ) -> list[RcaReport]:
        _authorize_access(
            store,
            settings.admin_approval_token,
            authorization,
            x_admin_token,
            {UserRole.ADMIN, UserRole.OPERATOR, UserRole.VIEWER},
        )
        return store.list_reports()

    @app.get("/api/rca/reports/{report_id}", response_model=RcaReport)
    def get_rca_report(
        report_id: str,
        authorization: str | None = Header(default=None, alias="Authorization"),
        x_admin_token: str | None = Header(default=None, alias="X-Admin-Token"),
    ) -> RcaReport:
        _authorize_access(
            store,
            settings.admin_approval_token,
            authorization,
            x_admin_token,
            {UserRole.ADMIN, UserRole.OPERATOR, UserRole.VIEWER},
        )
        report = store.get_report(report_id)
        if report is None:
            raise HTTPException(status_code=404, detail="RCA report not found")
        return report

    static_dir = Path(__file__).resolve().parent / "static"
    if static_dir.exists():
        app.mount("/static", StaticFiles(directory=static_dir), name="static")

        @app.get("/", include_in_schema=False)
        def web_console() -> FileResponse:
            return FileResponse(static_dir / "index.html")

    return app


def _verify_admin_token(configured_token: str, supplied_token: str | None) -> None:
    if not supplied_token or not secrets.compare_digest(configured_token, supplied_token):
        raise HTTPException(status_code=401, detail="invalid admin token")


def _verify_webhook_token(
    configured_token: str,
    authorization: str | None,
    supplied_webhook_token: str | None,
) -> None:
    candidate_tokens = [
        supplied_webhook_token.strip() if supplied_webhook_token else None,
        _extract_optional_bearer_token(authorization),
    ]
    if any(token and secrets.compare_digest(configured_token, token) for token in candidate_tokens):
        return
    raise HTTPException(status_code=401, detail="invalid webhook token")


def _authorize_access(
    store: StoreProtocol,
    configured_admin_token: str,
    authorization: str | None,
    supplied_admin_token: str | None,
    allowed_roles: set[UserRole],
) -> UserAccount | None:
    if authorization:
        try:
            return _require_user(store, authorization, allowed_roles)
        except HTTPException:
            if supplied_admin_token:
                _verify_admin_token(configured_admin_token, supplied_admin_token)
                return None
            raise
    if supplied_admin_token:
        _verify_admin_token(configured_admin_token, supplied_admin_token)
        return None
    raise HTTPException(status_code=401, detail="authentication required")


def _require_user(
    store: StoreProtocol,
    authorization: str | None,
    allowed_roles: set[UserRole],
) -> UserAccount:
    token = _extract_bearer_token(authorization)
    user = store.get_user_by_session_token(token)
    if user is None:
        raise HTTPException(status_code=401, detail="invalid or expired session")
    if user.status != UserStatus.ACTIVE or user.role is None:
        raise HTTPException(status_code=403, detail="user is not active")
    if user.role not in allowed_roles:
        raise HTTPException(status_code=403, detail="insufficient role")
    return user


def _extract_bearer_token(authorization: str | None) -> str:
    if not authorization:
        raise HTTPException(status_code=401, detail="missing bearer token")
    scheme, separator, token = authorization.partition(" ")
    if not separator or scheme.lower() != "bearer" or not token.strip():
        raise HTTPException(status_code=401, detail="invalid authorization header")
    return token.strip()


def _extract_optional_bearer_token(authorization: str | None) -> str | None:
    if not authorization:
        return None
    scheme, separator, token = authorization.partition(" ")
    if not separator or scheme.lower() != "bearer" or not token.strip():
        return None
    return token.strip()


def _verify_agent_token(store: StoreProtocol, cluster_id: str, agent_token: str) -> None:
    cluster = store.get_cluster(cluster_id)
    if cluster is None:
        raise HTTPException(status_code=404, detail="cluster not found")
    if not secrets.compare_digest(cluster.bootstrap_token, agent_token):
        raise HTTPException(status_code=401, detail="invalid agent token")


def _verify_agent_identity(
    store: StoreProtocol,
    cluster_id: str,
    node_name: str,
    agent_token: str,
    node_token: str,
) -> None:
    _verify_agent_token(store, cluster_id, agent_token)
    if store.get_agent(cluster_id, node_name) is None:
        raise HTTPException(status_code=404, detail="agent not registered")
    if not store.verify_agent_node_token(cluster_id, node_name, node_token):
        raise HTTPException(status_code=401, detail="invalid node token")


app = create_app()
