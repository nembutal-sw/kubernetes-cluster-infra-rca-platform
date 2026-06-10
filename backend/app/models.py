from __future__ import annotations

from datetime import datetime, timezone
from enum import Enum
from typing import Any

from pydantic import BaseModel, ConfigDict, Field


def now_utc() -> datetime:
    return datetime.now(timezone.utc)


class ClusterStatus(str, Enum):
    REGISTERED = "registered"
    AGENT_PENDING = "agent_pending"
    ACTIVE = "active"


class RcaJobStatus(str, Enum):
    COMPLETED = "completed"
    FAILED = "failed"


class PolicyLevel(str, Enum):
    AUTO_SAFE = "AUTO_SAFE"
    APPROVAL_REQUIRED = "APPROVAL_REQUIRED"
    GITOPS_PR_ONLY = "GITOPS_PR_ONLY"
    NEVER_AUTO_EXECUTE = "NEVER_AUTO_EXECUTE"
    MANUAL_INVESTIGATION = "MANUAL_INVESTIGATION"


class Confidence(str, Enum):
    LOW = "low"
    MEDIUM = "medium"
    HIGH = "high"


class ClusterCreateRequest(BaseModel):
    name: str = Field(min_length=1, examples=["prod-cluster"])
    environment: str = Field(default="dev", examples=["prod"])
    description: str | None = None


class Cluster(BaseModel):
    cluster_id: str
    name: str
    environment: str
    description: str | None = None
    status: ClusterStatus = ClusterStatus.AGENT_PENDING
    bootstrap_token: str
    created_at: datetime = Field(default_factory=now_utc)
    last_seen_at: datetime | None = None


class InstallCommandResponse(BaseModel):
    cluster_id: str
    namespace: str
    commands: list[str]
    notes: list[str]


class AlertmanagerAlert(BaseModel):
    model_config = ConfigDict(extra="allow", populate_by_name=True)

    status: str = "firing"
    labels: dict[str, str] = Field(default_factory=dict)
    annotations: dict[str, str] = Field(default_factory=dict)
    starts_at: datetime | None = Field(default=None, alias="startsAt")
    ends_at: datetime | None = Field(default=None, alias="endsAt")
    generator_url: str | None = Field(default=None, alias="generatorURL")


class AlertmanagerPayload(BaseModel):
    model_config = ConfigDict(extra="allow", populate_by_name=True)

    receiver: str | None = None
    status: str = "firing"
    alerts: list[AlertmanagerAlert] = Field(default_factory=list)
    group_labels: dict[str, str] = Field(default_factory=dict, alias="groupLabels")
    common_labels: dict[str, str] = Field(default_factory=dict, alias="commonLabels")
    external_url: str | None = Field(default=None, alias="externalURL")


class EvidenceBundle(BaseModel):
    evidence_id: str | None = None
    cluster_id: str
    node_name: str
    alert_name: str
    collected_at: datetime = Field(default_factory=now_utc)
    collectors: dict[str, Any]


class RcaSummary(BaseModel):
    symptom: str
    most_likely_cause: str
    confidence: Confidence


class RootCauseCandidate(BaseModel):
    cause: str
    confidence: Confidence
    supporting_evidence: list[str]


class RecommendedAction(BaseModel):
    action: str
    policy: PolicyLevel
    reason: str


class RcaReport(BaseModel):
    report_id: str
    cluster_id: str
    status: RcaJobStatus
    trigger: dict[str, Any]
    scope: dict[str, Any]
    summary: RcaSummary
    evidence: list[dict[str, Any]]
    root_cause_candidates: list[RootCauseCandidate]
    recommended_actions: list[RecommendedAction]
    policy_decisions: list[RecommendedAction]
    created_at: datetime = Field(default_factory=now_utc)


class RcaJob(BaseModel):
    job_id: str
    cluster_id: str
    alert_name: str
    node_name: str
    status: RcaJobStatus
    report_id: str
    evidence_id: str | None = None
    created_at: datetime = Field(default_factory=now_utc)


class WebhookIngestResponse(BaseModel):
    received_alerts: int
    created_jobs: list[RcaJob]
    created_reports: list[str]
    skipped_alerts: list[str]
