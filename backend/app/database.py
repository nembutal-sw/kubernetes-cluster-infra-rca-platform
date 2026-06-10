from __future__ import annotations

from collections.abc import Callable
from pathlib import Path

from sqlalchemy import create_engine
from sqlalchemy.engine import Engine
from sqlalchemy.engine.url import make_url
from sqlalchemy.orm import DeclarativeBase, Session, sessionmaker

from backend.app.config import normalize_database_url


class Base(DeclarativeBase):
    pass


def create_db_engine(database_url: str) -> Engine:
    normalized_url = normalize_database_url(database_url)
    _ensure_sqlite_parent_dir(normalized_url)
    connect_args = {"check_same_thread": False} if normalized_url.startswith("sqlite") else {}
    return create_engine(normalized_url, future=True, pool_pre_ping=True, connect_args=connect_args)


def create_session_factory(engine: Engine) -> Callable[[], Session]:
    return sessionmaker(bind=engine, autoflush=False, autocommit=False, expire_on_commit=False)


def create_tables(engine: Engine) -> None:
    import backend.app.db_models  # noqa: F401

    Base.metadata.create_all(bind=engine)


def _ensure_sqlite_parent_dir(database_url: str) -> None:
    url = make_url(database_url)
    if url.get_backend_name() != "sqlite":
        return
    if url.database in (None, "", ":memory:"):
        return
    Path(url.database).expanduser().parent.mkdir(parents=True, exist_ok=True)
