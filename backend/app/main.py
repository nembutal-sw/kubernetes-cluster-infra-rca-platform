from __future__ import annotations

import json
import secrets
from datetime import datetime, timedelta, timezone

from fastapi import FastAPI, Header, HTTPException, Query, Request, Response, status
from fastapi.responses import JSONResponse
from sqlalchemy import text
from sqlalchemy.exc import SQLAlchemyError

from backend.app.config import load_settings
from backend.app.database import create_db_engine, create_session_factory, create_tables
from backend.app.models import (
    ActionExecutionRequest,
    ActionExecutionResponse,
    AgentEvidencePollRequest,
    AgentEvidenceSubmitRequest,
    AgentStatus,
    AuthSessionResponse,
    AlertmanagerPayload,
    Cluster,
    ClusterCollectionRequest,
    ClusterCollectionResponse,
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
    PolicyLevel,
    RcaJob,
    RcaReport,
    UserAccount,
    UserLoginRequest,
    UserPasswordChangeRequest,
    UserRole,
    UserStatus,
    WebhookIngestResponse,
    now_utc,
)
from backend.app.services.analyzer import RuleBasedRcaAnalyzer
from backend.app.services.agent_manifest import (
    DEFAULT_AGENT_IMAGE,
    DEFAULT_AGENT_NAMESPACE,
    DEFAULT_COMMAND_TIMEOUT_SECONDS,
    DEFAULT_CONTROL_PLANE_PROBE_PORTS,
    DEFAULT_HTTP_TIMEOUT_SECONDS,
    DEFAULT_KUBERNETES_API_TIMEOUT_SECONDS,
    DEFAULT_POLL_INTERVAL_SECONDS,
    DEFAULT_SYSTEMD_COLLECTOR_MODE,
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
    try:
        store.ensure_default_admin(settings.default_admin_username, settings.default_admin_password)
    except SQLAlchemyError:
        if settings.auto_create_tables:
            raise

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
        return response

    @app.get("/", include_in_schema=False)
    def api_root() -> dict[str, object]:
        return {
            "service": "cluster-infra-rca-backend",
            "status": "api_only",
            "web_console": "spring-boot-web-console",
            "docs": "/docs",
            "health": "/health",
        }

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
    ) -> Cluster:
        _authorize_access(
            store,
            authorization,
            {UserRole.ADMIN, UserRole.OPERATOR},
        )
        return store.create_cluster(request)

    @app.post("/api/auth/login", response_model=AuthSessionResponse)
    def login(request: UserLoginRequest) -> AuthSessionResponse:
        user = store.authenticate_user(request.username, request.password)
        if user is None:
            raise HTTPException(status_code=401, detail="invalid username or password")
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

    @app.post("/api/auth/change-password")
    def change_password(
        request: UserPasswordChangeRequest,
        authorization: str | None = Header(default=None, alias="Authorization"),
    ) -> dict[str, bool]:
        user = _require_user(store, authorization, {UserRole.ADMIN, UserRole.OPERATOR, UserRole.VIEWER})
        if not store.change_user_password(user.user_id, request.current_password, request.new_password):
            raise HTTPException(status_code=401, detail="current password is invalid")
        return {"changed": True}

    @app.get("/api/clusters", response_model=list[ClusterView])
    def list_clusters(
        authorization: str | None = Header(default=None, alias="Authorization"),
    ) -> list[Cluster]:
        _authorize_access(
            store,
            authorization,
            {UserRole.ADMIN, UserRole.OPERATOR, UserRole.VIEWER},
        )
        return store.list_clusters()

    @app.get("/api/clusters/{cluster_id}", response_model=ClusterView)
    def get_cluster(
        cluster_id: str,
        authorization: str | None = Header(default=None, alias="Authorization"),
    ) -> Cluster:
        _authorize_access(
            store,
            authorization,
            {UserRole.ADMIN, UserRole.OPERATOR, UserRole.VIEWER},
        )
        cluster = store.get_cluster(cluster_id)
        if cluster is None:
            raise HTTPException(status_code=404, detail="cluster not found")
        return cluster

    @app.delete("/api/clusters/{cluster_id}")
    def delete_cluster(
        cluster_id: str,
        confirm_name: str = Query(..., min_length=1),
        authorization: str | None = Header(default=None, alias="Authorization"),
    ) -> dict[str, object]:
        _authorize_access(
            store,
            authorization,
            {UserRole.ADMIN},
        )
        cluster = store.get_cluster(cluster_id)
        if cluster is None:
            raise HTTPException(status_code=404, detail="cluster not found")
        if confirm_name != cluster.name:
            raise HTTPException(status_code=400, detail="confirm_name must match the cluster name")
        if not store.delete_cluster(cluster_id):
            raise HTTPException(status_code=404, detail="cluster not found")
        return {"deleted": True, "cluster_id": cluster_id, "name": cluster.name}

    @app.get("/api/clusters/{cluster_id}/install-command")
    def get_install_command(
        cluster_id: str,
        authorization: str | None = Header(default=None, alias="Authorization"),
        backend_url: str | None = Query(default=None),
        image: str = Query(default=DEFAULT_AGENT_IMAGE),
        namespace: str = Query(default=DEFAULT_AGENT_NAMESPACE),
    ):
        _authorize_access(
            store,
            authorization,
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
        kubernetes_api_timeout_seconds: int = Query(default=DEFAULT_KUBERNETES_API_TIMEOUT_SECONDS),
        control_plane_probe_ports: str = Query(default=DEFAULT_CONTROL_PLANE_PROBE_PORTS),
        runtime_socket_paths: str = Query(default=""),
        systemd_collector_mode: str = Query(default=DEFAULT_SYSTEMD_COLLECTOR_MODE),
        agent_token: str | None = Query(default=None),
        authorization: str | None = Header(default=None, alias="Authorization"),
    ) -> dict[str, object]:
        _authorize_agent_manifest_access(store, cluster_id, authorization, agent_token)
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
                    kubernetes_api_timeout_seconds=kubernetes_api_timeout_seconds,
                    control_plane_probe_ports=control_plane_probe_ports,
                    runtime_socket_paths=runtime_socket_paths,
                    systemd_collector_mode=systemd_collector_mode,
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
    ) -> list[NodeAgent]:
        _authorize_access(
            store,
            authorization,
            {UserRole.ADMIN, UserRole.OPERATOR, UserRole.VIEWER},
        )
        if store.get_cluster(cluster_id) is None:
            raise HTTPException(status_code=404, detail="cluster not found")
        return [
            _agent_with_freshness(agent, settings.agent_offline_after_seconds)
            for agent in store.list_agents(cluster_id)
        ]

    @app.get("/api/clusters/{cluster_id}/agents/{node_name}", response_model=NodeAgent)
    def get_cluster_agent(
        cluster_id: str,
        node_name: str,
        authorization: str | None = Header(default=None, alias="Authorization"),
    ) -> NodeAgent:
        _authorize_access(
            store,
            authorization,
            {UserRole.ADMIN, UserRole.OPERATOR, UserRole.VIEWER},
        )
        if store.get_cluster(cluster_id) is None:
            raise HTTPException(status_code=404, detail="cluster not found")
        agent = store.get_agent(cluster_id, node_name)
        if agent is None:
            raise HTTPException(status_code=404, detail="agent not found")
        return _agent_with_freshness(agent, settings.agent_offline_after_seconds)

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
    ) -> EvidenceRequest:
        _authorize_access(
            store,
            authorization,
            {UserRole.ADMIN, UserRole.OPERATOR},
        )
        if store.get_cluster(request.cluster_id) is None:
            raise HTTPException(status_code=404, detail="cluster not found")
        if store.get_agent(request.cluster_id, request.node_name) is None:
            raise HTTPException(status_code=404, detail="agent not found")
        return store.create_evidence_request(request)

    @app.post(
        "/api/clusters/{cluster_id}/collection-runs",
        response_model=ClusterCollectionResponse,
        status_code=status.HTTP_201_CREATED,
    )
    def create_cluster_collection_run(
        cluster_id: str,
        request: ClusterCollectionRequest,
        authorization: str | None = Header(default=None, alias="Authorization"),
    ) -> ClusterCollectionResponse:
        user = _require_user(store, authorization, {UserRole.ADMIN, UserRole.OPERATOR})
        if not request.confirmed:
            raise HTTPException(status_code=400, detail="collection confirmation is required")
        cluster = store.get_cluster(cluster_id)
        if cluster is None:
            raise HTTPException(status_code=404, detail="cluster not found")

        agents = {agent.node_name: agent for agent in store.list_agents(cluster_id)}
        target_nodes = _collection_target_nodes(request.node_names, agents)
        if not target_nodes:
            raise HTTPException(status_code=409, detail="cluster has no registered agents")

        requested_collectors = request.requested_collectors or _default_backend_collection_collectors()
        requested_at = now_utc().isoformat()
        created: list[EvidenceRequest] = []
        skipped: list[str] = []

        for node_name in target_nodes:
            agent = agents.get(node_name)
            if agent is None:
                skipped.append(f"{node_name}: agent not registered")
                continue
            fresh_agent = _agent_with_freshness(agent, settings.agent_offline_after_seconds)
            if fresh_agent.status == AgentStatus.OFFLINE:
                skipped.append(f"{node_name}: agent offline")
                continue
            created.append(
                store.create_evidence_request(
                    EvidenceRequestCreateRequest(
                        cluster_id=cluster.cluster_id,
                        node_name=node_name,
                        alert_name=request.alert_name,
                        requested_collectors=requested_collectors,
                        time_range={"source": "backend_collection", "requested_at": requested_at},
                        reason=request.reason,
                        context={
                            **request.context,
                            "trigger": "backend_collection",
                            "requested_by": user.email,
                            "requested_at": requested_at,
                        },
                    )
                )
            )

        return ClusterCollectionResponse(
            cluster_id=cluster.cluster_id,
            requested_nodes=target_nodes,
            created_evidence_requests=created,
            skipped_nodes=skipped,
        )

    @app.get("/api/clusters/{cluster_id}/evidence-requests", response_model=list[EvidenceRequest])
    def list_cluster_evidence_requests(
        cluster_id: str,
        authorization: str | None = Header(default=None, alias="Authorization"),
    ) -> list[EvidenceRequest]:
        _authorize_access(
            store,
            authorization,
            {UserRole.ADMIN, UserRole.OPERATOR, UserRole.VIEWER},
        )
        if store.get_cluster(cluster_id) is None:
            raise HTTPException(status_code=404, detail="cluster not found")
        return store.list_evidence_requests(cluster_id=cluster_id)

    @app.get("/api/evidence/requests/{request_id}", response_model=EvidenceRequest)
    def get_evidence_request(
        request_id: str,
        authorization: str | None = Header(default=None, alias="Authorization"),
    ) -> EvidenceRequest:
        _authorize_access(
            store,
            authorization,
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
    ) -> EvidenceBundle:
        _authorize_access(
            store,
            authorization,
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
    ) -> list[RcaJob]:
        _authorize_access(
            store,
            authorization,
            {UserRole.ADMIN, UserRole.OPERATOR, UserRole.VIEWER},
        )
        return store.list_jobs()

    @app.get("/api/rca/jobs/{job_id}", response_model=RcaJob)
    def get_rca_job(
        job_id: str,
        authorization: str | None = Header(default=None, alias="Authorization"),
    ) -> RcaJob:
        _authorize_access(
            store,
            authorization,
            {UserRole.ADMIN, UserRole.OPERATOR, UserRole.VIEWER},
        )
        job = store.get_job(job_id)
        if job is None:
            raise HTTPException(status_code=404, detail="RCA job not found")
        return job

    @app.get("/api/rca/reports", response_model=list[RcaReport])
    def list_rca_reports(
        authorization: str | None = Header(default=None, alias="Authorization"),
    ) -> list[RcaReport]:
        _authorize_access(
            store,
            authorization,
            {UserRole.ADMIN, UserRole.OPERATOR, UserRole.VIEWER},
        )
        return store.list_reports()

    @app.get("/api/rca/reports/export")
    def export_rca_reports(
        cluster_id: str | None = Query(default=None, min_length=1),
        export_format: str = Query(default="json", alias="format", pattern="^json$"),
        authorization: str | None = Header(default=None, alias="Authorization"),
    ) -> Response:
        del export_format
        _authorize_access(
            store,
            authorization,
            {UserRole.ADMIN, UserRole.OPERATOR, UserRole.VIEWER},
        )
        reports = store.list_reports()
        if cluster_id is not None:
            reports = [report for report in reports if report.cluster_id == cluster_id]
        payload = _report_export_payload(reports, {"cluster_id": cluster_id})
        return _json_attachment_response(payload, _report_export_filename(cluster_id))

    @app.get("/api/rca/reports/{report_id}", response_model=RcaReport)
    def get_rca_report(
        report_id: str,
        authorization: str | None = Header(default=None, alias="Authorization"),
    ) -> RcaReport:
        _authorize_access(
            store,
            authorization,
            {UserRole.ADMIN, UserRole.OPERATOR, UserRole.VIEWER},
        )
        report = store.get_report(report_id)
        if report is None:
            raise HTTPException(status_code=404, detail="RCA report not found")
        return report

    @app.get("/api/rca/reports/{report_id}/export")
    def export_rca_report(
        report_id: str,
        export_format: str = Query(default="json", alias="format", pattern="^json$"),
        authorization: str | None = Header(default=None, alias="Authorization"),
    ) -> Response:
        del export_format
        _authorize_access(
            store,
            authorization,
            {UserRole.ADMIN, UserRole.OPERATOR, UserRole.VIEWER},
        )
        report = store.get_report(report_id)
        if report is None:
            raise HTTPException(status_code=404, detail="RCA report not found")
        payload = _report_export_payload([report], {"report_id": report_id})
        return _json_attachment_response(payload, _report_export_filename(report_id))

    @app.post("/api/rca/reports/{report_id}/actions/{action_index}/execute", response_model=ActionExecutionResponse)
    def execute_rca_action(
        report_id: str,
        action_index: int,
        request: ActionExecutionRequest,
        authorization: str | None = Header(default=None, alias="Authorization"),
    ) -> ActionExecutionResponse:
        user = _require_user(store, authorization, {UserRole.ADMIN, UserRole.OPERATOR})
        if not request.confirmed:
            raise HTTPException(status_code=400, detail="action confirmation is required")

        report = store.get_report(report_id)
        if report is None:
            raise HTTPException(status_code=404, detail="RCA report not found")
        if action_index < 0 or action_index >= len(report.recommended_actions):
            raise HTTPException(status_code=404, detail="recommended action not found")

        action = report.recommended_actions[action_index]
        if not action.automation_allowed:
            return _blocked_action_response(report, action_index, action)

        node_name = _target_node_for_report(report)
        if node_name is None:
            return ActionExecutionResponse(
                report_id=report.report_id,
                action_index=action_index,
                action_key=action.action_key,
                policy=action.policy,
                status="blocked",
                message="No target node was found in the RCA report scope.",
                guardrails=[*action.guardrails, "missing_target_node"],
            )
        if store.get_agent(report.cluster_id, node_name) is None:
            return ActionExecutionResponse(
                report_id=report.report_id,
                action_index=action_index,
                action_key=action.action_key,
                policy=action.policy,
                status="blocked",
                message="Target node agent is not registered, so evidence collection cannot be requested.",
                guardrails=[*action.guardrails, "agent_not_registered"],
            )

        evidence_request = store.create_evidence_request(
            EvidenceRequestCreateRequest(
                cluster_id=report.cluster_id,
                node_name=node_name,
                alert_name=str(report.trigger.get("alert_name") or report.summary.symptom or "RcaFollowUp"),
                requested_collectors=_collectors_for_action(action.action_key),
                time_range={"source": "rca_action", "report_created_at": report.created_at.isoformat()},
                reason=f"RCA action confirmed: {action.action}",
                context={
                    "report_id": report.report_id,
                    "action_index": action_index,
                    "action_key": action.action_key,
                    "action_source": action.source,
                    "policy": action.policy.value,
                    "requested_by": user.email,
                    "note": request.note,
                },
            )
        )
        return ActionExecutionResponse(
            report_id=report.report_id,
            action_index=action_index,
            action_key=action.action_key,
            policy=action.policy,
            status="accepted",
            message="Read-only evidence collection was requested for the node agent.",
            execution_started=True,
            evidence_request=evidence_request,
            guardrails=action.guardrails,
        )

    return app


def _collection_target_nodes(requested_nodes: list[str], agents: dict[str, NodeAgent]) -> list[str]:
    if requested_nodes:
        return _dedupe_strings(requested_nodes)
    return sorted(agents)


def _default_backend_collection_collectors() -> list[str]:
    return [
        "node",
        "kubernetes",
        "systemd",
        "runtime",
        "kernel",
        "disk",
        "inode",
        "memory",
        "network",
        "cni",
        "dns",
        "conntrack",
        "process",
    ]


def _blocked_action_response(
    report: RcaReport,
    action_index: int,
    action,
) -> ActionExecutionResponse:
    if action.policy == PolicyLevel.APPROVAL_REQUIRED:
        status_value = "approval_required"
        message = "This action changes node or service state and requires an operator approval workflow."
    elif action.policy == PolicyLevel.GITOPS_PR_ONLY:
        status_value = "pr_required"
        message = "This action must be proposed through a reviewable GitOps pull request."
    elif action.policy == PolicyLevel.NEVER_AUTO_EXECUTE:
        status_value = "blocked"
        message = "Policy prohibits automatic execution for this action."
    elif action.source == "llm":
        status_value = "review_required"
        message = "LLM-originated actions cannot trigger direct automation without rule-based review."
    else:
        status_value = "manual_required"
        message = "This action is not eligible for automatic execution."
    return ActionExecutionResponse(
        report_id=report.report_id,
        action_index=action_index,
        action_key=action.action_key,
        policy=action.policy,
        status=status_value,
        message=message,
        requires_approval=action.requires_approval or action.review_required or action.source == "llm",
        guardrails=action.guardrails,
    )


def _report_export_payload(reports: list[RcaReport], filters: dict[str, str | None]) -> dict[str, object]:
    return {
        "format_version": "rca-report-export-v1",
        "exported_at": now_utc().isoformat(),
        "report_count": len(reports),
        "filters": {key: value for key, value in filters.items() if value},
        "reports": [report.model_dump(mode="json") for report in reports],
    }


def _json_attachment_response(payload: dict[str, object], filename: str) -> Response:
    return Response(
        content=json.dumps(payload, ensure_ascii=False, indent=2),
        media_type="application/json; charset=utf-8",
        headers={"Content-Disposition": f'attachment; filename="{filename}"'},
    )


def _report_export_filename(identifier: str | None = None) -> str:
    timestamp = now_utc().strftime("%Y%m%dT%H%M%SZ")
    safe_identifier = _safe_export_filename_part(identifier)
    if safe_identifier:
        return f"rca-reports-{safe_identifier}-{timestamp}.json"
    return f"rca-reports-{timestamp}.json"


def _safe_export_filename_part(value: str | None) -> str:
    if not value:
        return ""
    safe = "".join(char if char.isalnum() or char in ("-", "_", ".") else "-" for char in value.strip())
    return safe.strip(".-_")[:80]


def _target_node_for_report(report: RcaReport) -> str | None:
    nodes = report.scope.get("nodes")
    if isinstance(nodes, list):
        for node in nodes:
            if isinstance(node, str) and node.strip():
                return node.strip()
    for key in ("node", "node_name", "instance"):
        value = report.trigger.get(key)
        if isinstance(value, str) and value.strip():
            return value.strip()
    return None


def _collectors_for_action(action_key: str | None) -> list[str]:
    collector_map = {
        "collect_more_evidence": [
            "node",
            "kubernetes",
            "systemd",
            "runtime",
            "kernel",
            "disk",
            "inode",
            "memory",
            "network",
            "cni",
            "dns",
            "conntrack",
            "process",
        ],
        "collect_linux_low_level_evidence": [
            "node",
            "systemd",
            "kubelet",
            "runtime",
            "kernel",
            "disk",
            "inode",
            "memory",
            "network",
            "conntrack",
            "process",
        ],
        "inspect_kernel_state": ["kernel", "systemd", "kubelet"],
        "inspect_network_state": ["network", "cni", "dns", "conntrack", "kernel"],
        "inspect_storage_state": ["disk", "inode", "kernel", "systemd"],
    }
    return collector_map.get(action_key or "", collector_map["collect_more_evidence"])


def _dedupe_strings(values: list[str]) -> list[str]:
    result = []
    seen = set()
    for value in values:
        normalized = str(value or "").strip()
        if not normalized or normalized in seen:
            continue
        seen.add(normalized)
        result.append(normalized)
    return result


def _agent_with_freshness(agent: NodeAgent, offline_after_seconds: int) -> NodeAgent:
    last_seen_at = agent.last_heartbeat_at or agent.registered_at
    age_seconds = _age_seconds(last_seen_at)
    is_offline = age_seconds is not None and age_seconds > offline_after_seconds
    health = {
        **agent.health,
        "freshness": {
            "last_seen_at": last_seen_at.isoformat() if last_seen_at else None,
            "age_seconds": age_seconds,
            "offline_after_seconds": offline_after_seconds,
            "offline": is_offline,
        },
    }
    return agent.model_copy(
        update={
            "status": AgentStatus.OFFLINE if is_offline else agent.status,
            "health": health,
        }
    )


def _age_seconds(value: datetime | None) -> int | None:
    if value is None:
        return None
    if value.tzinfo is None:
        value = value.replace(tzinfo=timezone.utc)
    return max(0, int((now_utc() - value).total_seconds()))


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
    authorization: str | None,
    allowed_roles: set[UserRole],
) -> UserAccount | None:
    return _require_user(store, authorization, allowed_roles)


def _authorize_agent_manifest_access(
    store: StoreProtocol,
    cluster_id: str,
    authorization: str | None,
    agent_token: str | None,
) -> None:
    cluster = store.get_cluster(cluster_id)
    if cluster is None:
        raise HTTPException(status_code=404, detail="cluster not found")

    session_token = _extract_optional_bearer_token(authorization)
    if session_token:
        user = store.get_user_by_session_token(session_token)
        if user is not None:
            if user.status != UserStatus.ACTIVE or user.role is None:
                raise HTTPException(status_code=403, detail="user is not active")
            if user.role not in {UserRole.ADMIN, UserRole.OPERATOR}:
                raise HTTPException(status_code=403, detail="insufficient role")
            return

    if agent_token:
        if secrets.compare_digest(cluster.bootstrap_token, agent_token):
            return
        raise HTTPException(status_code=401, detail="invalid agent token")

    if authorization:
        raise HTTPException(status_code=401, detail="invalid or expired session")
    raise HTTPException(status_code=401, detail="agent manifest authentication required")


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
