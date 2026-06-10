from __future__ import annotations

from datetime import datetime

from sqlalchemy import DateTime, ForeignKey, String, Text, UniqueConstraint
from sqlalchemy.orm import Mapped, mapped_column

from backend.app.database import Base


class ClusterRow(Base):
    __tablename__ = "clusters"

    cluster_id: Mapped[str] = mapped_column(String(64), primary_key=True)
    name: Mapped[str] = mapped_column(String(255), nullable=False)
    environment: Mapped[str] = mapped_column(String(64), nullable=False)
    description: Mapped[str | None] = mapped_column(Text, nullable=True)
    status: Mapped[str] = mapped_column(String(32), nullable=False)
    bootstrap_token: Mapped[str] = mapped_column(String(255), nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    last_seen_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)


class EvidenceBundleRow(Base):
    __tablename__ = "evidence_bundles"

    evidence_id: Mapped[str] = mapped_column(String(64), primary_key=True)
    cluster_id: Mapped[str] = mapped_column(ForeignKey("clusters.cluster_id"), nullable=False, index=True)
    node_name: Mapped[str] = mapped_column(String(255), nullable=False)
    alert_name: Mapped[str] = mapped_column(String(255), nullable=False)
    collectors_json: Mapped[str] = mapped_column(Text, nullable=False)
    collected_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)


class NodeAgentRow(Base):
    __tablename__ = "node_agents"
    __table_args__ = (UniqueConstraint("cluster_id", "node_name", name="uq_node_agents_cluster_node"),)

    agent_id: Mapped[str] = mapped_column(String(64), primary_key=True)
    cluster_id: Mapped[str] = mapped_column(ForeignKey("clusters.cluster_id"), nullable=False, index=True)
    node_name: Mapped[str] = mapped_column(String(255), nullable=False)
    node_token_hash: Mapped[str] = mapped_column(String(512), nullable=False)
    agent_version: Mapped[str] = mapped_column(String(64), nullable=False)
    status: Mapped[str] = mapped_column(String(32), nullable=False)
    supported_collectors_json: Mapped[str] = mapped_column(Text, nullable=False)
    metadata_json: Mapped[str] = mapped_column(Text, nullable=False)
    health_json: Mapped[str] = mapped_column(Text, nullable=False)
    registered_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    last_heartbeat_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)


class UserAccountRow(Base):
    __tablename__ = "user_accounts"
    __table_args__ = (UniqueConstraint("email", name="uq_user_accounts_email"),)

    user_id: Mapped[str] = mapped_column(String(64), primary_key=True)
    email: Mapped[str] = mapped_column(String(255), nullable=False, index=True)
    full_name: Mapped[str] = mapped_column(String(255), nullable=False)
    password_hash: Mapped[str] = mapped_column(String(512), nullable=False)
    requested_role: Mapped[str] = mapped_column(String(32), nullable=False)
    role: Mapped[str | None] = mapped_column(String(32), nullable=True)
    status: Mapped[str] = mapped_column(String(32), nullable=False, index=True)
    reason: Mapped[str | None] = mapped_column(Text, nullable=True)
    approval_note: Mapped[str | None] = mapped_column(Text, nullable=True)
    approved_by: Mapped[str | None] = mapped_column(String(255), nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    approved_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)


class UserSessionRow(Base):
    __tablename__ = "user_sessions"

    session_id: Mapped[str] = mapped_column(String(64), primary_key=True)
    user_id: Mapped[str] = mapped_column(ForeignKey("user_accounts.user_id"), nullable=False, index=True)
    token_hash: Mapped[str] = mapped_column(String(128), nullable=False, unique=True, index=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, index=True)
    revoked_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)


class EvidenceRequestRow(Base):
    __tablename__ = "evidence_requests"

    request_id: Mapped[str] = mapped_column(String(64), primary_key=True)
    cluster_id: Mapped[str] = mapped_column(ForeignKey("clusters.cluster_id"), nullable=False, index=True)
    node_name: Mapped[str] = mapped_column(String(255), nullable=False, index=True)
    alert_name: Mapped[str] = mapped_column(String(255), nullable=False)
    requested_collectors_json: Mapped[str] = mapped_column(Text, nullable=False)
    status: Mapped[str] = mapped_column(String(32), nullable=False, index=True)
    time_range_json: Mapped[str] = mapped_column(Text, nullable=False)
    reason: Mapped[str | None] = mapped_column(Text, nullable=True)
    context_json: Mapped[str] = mapped_column(Text, nullable=False)
    evidence_id: Mapped[str | None] = mapped_column(ForeignKey("evidence_bundles.evidence_id"), nullable=True, index=True)
    error_message: Mapped[str | None] = mapped_column(Text, nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    completed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)


class RcaReportRow(Base):
    __tablename__ = "rca_reports"

    report_id: Mapped[str] = mapped_column(String(64), primary_key=True)
    cluster_id: Mapped[str] = mapped_column(ForeignKey("clusters.cluster_id"), nullable=False, index=True)
    status: Mapped[str] = mapped_column(String(32), nullable=False)
    trigger_json: Mapped[str] = mapped_column(Text, nullable=False)
    scope_json: Mapped[str] = mapped_column(Text, nullable=False)
    summary_json: Mapped[str] = mapped_column(Text, nullable=False)
    evidence_json: Mapped[str] = mapped_column(Text, nullable=False)
    root_cause_candidates_json: Mapped[str] = mapped_column(Text, nullable=False)
    recommended_actions_json: Mapped[str] = mapped_column(Text, nullable=False)
    policy_decisions_json: Mapped[str] = mapped_column(Text, nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)


class RcaJobRow(Base):
    __tablename__ = "rca_jobs"

    job_id: Mapped[str] = mapped_column(String(64), primary_key=True)
    cluster_id: Mapped[str] = mapped_column(ForeignKey("clusters.cluster_id"), nullable=False, index=True)
    alert_name: Mapped[str] = mapped_column(String(255), nullable=False)
    node_name: Mapped[str] = mapped_column(String(255), nullable=False)
    status: Mapped[str] = mapped_column(String(32), nullable=False)
    report_id: Mapped[str] = mapped_column(ForeignKey("rca_reports.report_id"), nullable=False, index=True)
    evidence_id: Mapped[str | None] = mapped_column(ForeignKey("evidence_bundles.evidence_id"), nullable=True, index=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
