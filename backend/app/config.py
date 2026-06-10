from __future__ import annotations

import os
from dataclasses import dataclass


DEFAULT_DATABASE_URL = "sqlite:///./data/rca-dev.db"


@dataclass(frozen=True)
class Settings:
    database_url: str = DEFAULT_DATABASE_URL
    auto_create_tables: bool = True


def load_settings(database_url: str | None = None) -> Settings:
    return Settings(
        database_url=database_url or os.getenv("RCA_DATABASE_URL", DEFAULT_DATABASE_URL),
        auto_create_tables=os.getenv("RCA_AUTO_CREATE_TABLES", "true").lower() != "false",
    )


def normalize_database_url(database_url: str) -> str:
    if database_url.startswith("postgres://"):
        return "postgresql+psycopg://" + database_url.removeprefix("postgres://")
    if database_url.startswith("postgresql://"):
        return "postgresql+psycopg://" + database_url.removeprefix("postgresql://")
    if database_url.startswith("mariadb://"):
        return "mysql+pymysql://" + database_url.removeprefix("mariadb://")
    if database_url.startswith("mysql://"):
        return "mysql+pymysql://" + database_url.removeprefix("mysql://")
    return database_url

