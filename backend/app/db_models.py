from __future__ import annotations

from datetime import datetime

from sqlalchemy import DateTime, ForeignKey, String, Text
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
    evidence_id: Mapped[str | None] = mapped_column(ForeignKey("evidence_bundles.evidence_id"), nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)

