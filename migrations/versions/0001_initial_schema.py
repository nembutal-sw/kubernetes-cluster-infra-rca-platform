"""initial schema

Revision ID: 0001_initial_schema
Revises:
Create Date: 2026-06-10 00:00:00
"""

from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


revision: str = "0001_initial_schema"
down_revision: Union[str, None] = None
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.create_table(
        "clusters",
        sa.Column("cluster_id", sa.String(length=64), nullable=False),
        sa.Column("name", sa.String(length=255), nullable=False),
        sa.Column("environment", sa.String(length=64), nullable=False),
        sa.Column("description", sa.Text(), nullable=True),
        sa.Column("status", sa.String(length=32), nullable=False),
        sa.Column("bootstrap_token", sa.String(length=255), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("last_seen_at", sa.DateTime(timezone=True), nullable=True),
        sa.PrimaryKeyConstraint("cluster_id"),
    )

    op.create_table(
        "evidence_bundles",
        sa.Column("evidence_id", sa.String(length=64), nullable=False),
        sa.Column("cluster_id", sa.String(length=64), nullable=False),
        sa.Column("node_name", sa.String(length=255), nullable=False),
        sa.Column("alert_name", sa.String(length=255), nullable=False),
        sa.Column("collectors_json", sa.Text(), nullable=False),
        sa.Column("collected_at", sa.DateTime(timezone=True), nullable=False),
        sa.ForeignKeyConstraint(["cluster_id"], ["clusters.cluster_id"]),
        sa.PrimaryKeyConstraint("evidence_id"),
    )
    op.create_index(
        "ix_evidence_bundles_cluster_id",
        "evidence_bundles",
        ["cluster_id"],
    )

    op.create_table(
        "rca_reports",
        sa.Column("report_id", sa.String(length=64), nullable=False),
        sa.Column("cluster_id", sa.String(length=64), nullable=False),
        sa.Column("status", sa.String(length=32), nullable=False),
        sa.Column("trigger_json", sa.Text(), nullable=False),
        sa.Column("scope_json", sa.Text(), nullable=False),
        sa.Column("summary_json", sa.Text(), nullable=False),
        sa.Column("evidence_json", sa.Text(), nullable=False),
        sa.Column("root_cause_candidates_json", sa.Text(), nullable=False),
        sa.Column("recommended_actions_json", sa.Text(), nullable=False),
        sa.Column("policy_decisions_json", sa.Text(), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.ForeignKeyConstraint(["cluster_id"], ["clusters.cluster_id"]),
        sa.PrimaryKeyConstraint("report_id"),
    )
    op.create_index("ix_rca_reports_cluster_id", "rca_reports", ["cluster_id"])

    op.create_table(
        "rca_jobs",
        sa.Column("job_id", sa.String(length=64), nullable=False),
        sa.Column("cluster_id", sa.String(length=64), nullable=False),
        sa.Column("alert_name", sa.String(length=255), nullable=False),
        sa.Column("node_name", sa.String(length=255), nullable=False),
        sa.Column("status", sa.String(length=32), nullable=False),
        sa.Column("report_id", sa.String(length=64), nullable=False),
        sa.Column("evidence_id", sa.String(length=64), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.ForeignKeyConstraint(["cluster_id"], ["clusters.cluster_id"]),
        sa.ForeignKeyConstraint(["evidence_id"], ["evidence_bundles.evidence_id"]),
        sa.ForeignKeyConstraint(["report_id"], ["rca_reports.report_id"]),
        sa.PrimaryKeyConstraint("job_id"),
    )
    op.create_index("ix_rca_jobs_cluster_id", "rca_jobs", ["cluster_id"])
    op.create_index("ix_rca_jobs_evidence_id", "rca_jobs", ["evidence_id"])
    op.create_index("ix_rca_jobs_report_id", "rca_jobs", ["report_id"])


def downgrade() -> None:
    op.drop_index("ix_rca_jobs_report_id", table_name="rca_jobs")
    op.drop_index("ix_rca_jobs_evidence_id", table_name="rca_jobs")
    op.drop_index("ix_rca_jobs_cluster_id", table_name="rca_jobs")
    op.drop_table("rca_jobs")

    op.drop_index("ix_rca_reports_cluster_id", table_name="rca_reports")
    op.drop_table("rca_reports")

    op.drop_index("ix_evidence_bundles_cluster_id", table_name="evidence_bundles")
    op.drop_table("evidence_bundles")

    op.drop_table("clusters")

