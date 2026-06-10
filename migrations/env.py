from __future__ import annotations

import os
from logging.config import fileConfig

from alembic import context

from backend.app.config import load_settings, normalize_database_url
from backend.app.database import Base, create_db_engine

import backend.app.db_models  # noqa: F401


config = context.config

if config.config_file_name is not None:
    fileConfig(config.config_file_name)

target_metadata = Base.metadata


def _database_url() -> str:
    configured_url = os.getenv("RCA_DATABASE_URL") or config.get_main_option("sqlalchemy.url")
    settings = load_settings(database_url=configured_url, auto_create_tables=False)
    return normalize_database_url(settings.database_url)


def run_migrations_offline() -> None:
    context.configure(
        url=_database_url(),
        target_metadata=target_metadata,
        literal_binds=True,
        dialect_opts={"paramstyle": "named"},
        compare_type=True,
    )

    with context.begin_transaction():
        context.run_migrations()


def run_migrations_online() -> None:
    engine = create_db_engine(_database_url())

    with engine.connect() as connection:
        context.configure(
            connection=connection,
            target_metadata=target_metadata,
            compare_type=True,
        )

        with context.begin_transaction():
            context.run_migrations()


if context.is_offline_mode():
    run_migrations_offline()
else:
    run_migrations_online()
