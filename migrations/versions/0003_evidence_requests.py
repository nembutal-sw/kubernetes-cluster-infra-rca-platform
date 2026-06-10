"""evidence requests

Revision ID: 0003_evidence_requests
Revises: 0002_node_agents
Create Date: 2026-06-10 00:00:00
"""

from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


revision: str = "0003_evidence_requests"
down_revision: Union[str, None] = "0002_node_agents"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.create_table(
        "evidence_requests",
        sa.Column("request_id", sa.String(length=64), nullable=False),
        sa.Column("cluster_id", sa.String(length=64), nullable=False),
        sa.Column("node_name", sa.String(length=255), nullable=False),
        sa.Column("alert_name", sa.String(length=255), nullable=False),
        sa.Column("requested_collectors_json", sa.Text(), nullable=False),
        sa.Column("status", sa.String(length=32), nullable=False),
        sa.Column("time_range_json", sa.Text(), nullable=False),
        sa.Column("reason", sa.Text(), nullable=True),
        sa.Column("context_json", sa.Text(), nullable=False),
        sa.Column("evidence_id", sa.String(length=64), nullable=True),
        sa.Column("error_message", sa.Text(), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("completed_at", sa.DateTime(timezone=True), nullable=True),
        sa.ForeignKeyConstraint(["cluster_id"], ["clusters.cluster_id"]),
        sa.ForeignKeyConstraint(["evidence_id"], ["evidence_bundles.evidence_id"]),
        sa.PrimaryKeyConstraint("request_id"),
    )
    op.create_index("ix_evidence_requests_cluster_id", "evidence_requests", ["cluster_id"])
    op.create_index("ix_evidence_requests_evidence_id", "evidence_requests", ["evidence_id"])
    op.create_index("ix_evidence_requests_node_name", "evidence_requests", ["node_name"])
    op.create_index("ix_evidence_requests_status", "evidence_requests", ["status"])


def downgrade() -> None:
    op.drop_index("ix_evidence_requests_status", table_name="evidence_requests")
    op.drop_index("ix_evidence_requests_node_name", table_name="evidence_requests")
    op.drop_index("ix_evidence_requests_evidence_id", table_name="evidence_requests")
    op.drop_index("ix_evidence_requests_cluster_id", table_name="evidence_requests")
    op.drop_table("evidence_requests")

