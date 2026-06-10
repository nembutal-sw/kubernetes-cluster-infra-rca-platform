"""node agent token hash

Revision ID: 0005_node_agent_token_hash
Revises: 0004_user_accounts
Create Date: 2026-06-10 00:00:00
"""

from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


revision: str = "0005_node_agent_token_hash"
down_revision: Union[str, None] = "0004_user_accounts"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.add_column(
        "node_agents",
        sa.Column(
            "node_token_hash",
            sa.String(length=512),
            nullable=False,
            server_default="registration-required",
        ),
    )


def downgrade() -> None:
    op.drop_column("node_agents", "node_token_hash")
