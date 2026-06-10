"""node agents

Revision ID: 0002_node_agents
Revises: 0001_initial_schema
Create Date: 2026-06-10 00:00:00
"""

from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


revision: str = "0002_node_agents"
down_revision: Union[str, None] = "0001_initial_schema"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.create_table(
        "node_agents",
        sa.Column("agent_id", sa.String(length=64), nullable=False),
        sa.Column("cluster_id", sa.String(length=64), nullable=False),
        sa.Column("node_name", sa.String(length=255), nullable=False),
        sa.Column("agent_version", sa.String(length=64), nullable=False),
        sa.Column("status", sa.String(length=32), nullable=False),
        sa.Column("supported_collectors_json", sa.Text(), nullable=False),
        sa.Column("metadata_json", sa.Text(), nullable=False),
        sa.Column("health_json", sa.Text(), nullable=False),
        sa.Column("registered_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("last_heartbeat_at", sa.DateTime(timezone=True), nullable=True),
        sa.ForeignKeyConstraint(["cluster_id"], ["clusters.cluster_id"]),
        sa.PrimaryKeyConstraint("agent_id"),
        sa.UniqueConstraint("cluster_id", "node_name", name="uq_node_agents_cluster_node"),
    )
    op.create_index("ix_node_agents_cluster_id", "node_agents", ["cluster_id"])


def downgrade() -> None:
    op.drop_index("ix_node_agents_cluster_id", table_name="node_agents")
    op.drop_table("node_agents")

